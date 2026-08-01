package opengpu.v2.persist;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import opengpu.v2.protocol.V2Wire;

/**
 * File-backed {@link ResourceStore}: {@code root/<dir(sceneId)>/<resId>.bin} for bodies and
 * {@code structure.dat} for the NBT-ceiling spill blob, with writes on a single background
 * thread. Directory names are made INJECTIVE by suffixing a content hash of the raw scene id
 * (plain character-folding would let distinct ids collide, and restore-time orphan pruning
 * would then cross-delete another scene's bodies).
 *
 * Atomicity: writes go to a {@code .tmp} sibling, fsync, then an ATOMIC_MOVE rename over the
 * target (no window where neither file exists). On exotic filesystems without atomic moves
 * the fallback is delete+rename — there a crash inside the window degrades that one body to
 * blank at restore (the documented degraded path), never a truncated body.
 *
 * Threading: this class is SELF-SYNCHRONIZING — callers hold per-scene locks, but the store
 * is world-scoped and may be reached from several of them; all public methods synchronize on
 * the store. The background thread only touches files and completes futures. {@link #load}
 * and {@link #delete} join the key's pending write first (the load-observes-save contract).
 */
public final class DirectoryResourceStore implements ResourceStore {
	private static final Charset UTF8 = Charset.forName("UTF-8");
	private static final String STRUCTURE_NAME = "structure.dat";

	private final File root;
	private final ExecutorService writer;
	private final Map<String, Future<?>> pendingSaves = new HashMap<String, Future<?>>();

	public DirectoryResourceStore(File root) {
		this.root = root;
		this.writer = Executors.newSingleThreadExecutor(new ThreadFactory() {
			@Override
			public Thread newThread(Runnable r) {
				Thread thread = new Thread(r, "OpenGPU Resource Store");
				thread.setDaemon(true);
				return thread;
			}
		});
	}

	private static String sanitize(String sceneId) {
		StringBuilder sb = new StringBuilder(sceneId.length() + 17);
		for (int i = 0; i < sceneId.length(); i++) {
			char c = sceneId.charAt(i);
			sb.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
					|| (c >= '0' && c <= '9') || c == '-' ? c : '_');
		}
		// Injectivity + case-insensitive-filesystem safety: the hash disambiguates ids that
		// fold to the same characters.
		sb.append('-').append(Long.toHexString(V2Wire.contentHash(sceneId.getBytes(UTF8))));
		return sb.toString();
	}

	private File sceneDir(String sceneId) {
		return new File(root, sanitize(sceneId));
	}

	private File bodyFile(String sceneId, int resId) {
		return new File(sceneDir(sceneId), resId + ".bin");
	}

	private static String key(String sceneId, String name) {
		return sceneId + "\0" + name;
	}

	private void joinPending(String key) {
		Future<?> pending = pendingSaves.remove(key);
		if (pending != null) {
			try {
				pending.get();
			} catch (Exception e) {
				// The write failed; the caller's load will observe the absence and degrade.
			}
		}
	}

	private void submitWrite(String key, final File dir, final File target, byte[] bytes) {
		final byte[] copy = bytes.clone();
		joinPending(key); // serialize per key so replaces cannot interleave stale data
		pendingSaves.put(key, writer.submit(new Runnable() {
			@Override
			public void run() {
				try {
					if (!dir.isDirectory() && !dir.mkdirs()) {
						throw new IOException("Cannot create " + dir);
					}
					File tmp = new File(dir, target.getName() + ".tmp");
					FileOutputStream out = new FileOutputStream(tmp);
					try {
						out.write(copy);
						out.getFD().sync();
					} finally {
						out.close();
					}
					try {
						Files.move(tmp.toPath(), target.toPath(),
								StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
					} catch (AtomicMoveNotSupportedException e) {
						// Non-atomic fallback: a crash inside this window degrades the body
						// to blank at restore — survivable, documented, and rare.
						if (target.exists() && !target.delete()) {
							throw new IOException("Cannot replace " + target);
						}
						if (!tmp.renameTo(target)) {
							throw new IOException("Cannot rename " + tmp + " to " + target);
						}
					}
				} catch (IOException e) {
					System.err.println("[OpenGPU] Resource store write failed for "
							+ target + ": " + e);
				}
			}
		}));
	}

	private byte[] readFile(File file) {
		if (!file.isFile())
			return null;
		long length = file.length();
		if (length < 0 || length > Integer.MAX_VALUE)
			return null;
		byte[] bytes = new byte[(int) length];
		try {
			FileInputStream in = new FileInputStream(file);
			try {
				int off = 0;
				while (off < bytes.length) {
					int read = in.read(bytes, off, bytes.length - off);
					if (read < 0)
						return null; // shorter than length(): treat as unreadable
					off += read;
				}
			} finally {
				in.close();
			}
		} catch (IOException e) {
			return null;
		}
		return bytes;
	}

	@Override
	public synchronized void save(String sceneId, int resId, byte[] bytes) {
		submitWrite(key(sceneId, resId + ".bin"), sceneDir(sceneId), bodyFile(sceneId, resId), bytes);
	}

	@Override
	public synchronized byte[] load(String sceneId, int resId) {
		joinPending(key(sceneId, resId + ".bin"));
		return readFile(bodyFile(sceneId, resId));
	}

	@Override
	public synchronized boolean contains(String sceneId, int resId) {
		return pendingSaves.containsKey(key(sceneId, resId + ".bin"))
				|| bodyFile(sceneId, resId).isFile();
	}

	@Override
	public synchronized void delete(String sceneId, int resId) {
		joinPending(key(sceneId, resId + ".bin"));
		File file = bodyFile(sceneId, resId);
		if (file.isFile()) {
			file.delete();
		}
	}

	@Override
	public synchronized void deleteScene(String sceneId) {
		String prefix = sceneId + "\0";
		List<String> keys = new ArrayList<String>(pendingSaves.keySet());
		for (String key : keys) {
			if (key.startsWith(prefix)) {
				joinPending(key);
			}
		}
		File dir = sceneDir(sceneId);
		File[] files = dir.listFiles();
		if (files != null) {
			for (File file : files) {
				file.delete();
			}
		}
		dir.delete();
	}

	@Override
	public synchronized List<Integer> listResources(String sceneId) {
		flushLocked();
		ArrayList<Integer> ids = new ArrayList<Integer>();
		File[] files = sceneDir(sceneId).listFiles();
		if (files != null) {
			for (File file : files) {
				String name = file.getName();
				if (name.endsWith(".bin")) {
					try {
						ids.add(Integer.parseInt(name.substring(0, name.length() - 4)));
					} catch (NumberFormatException ignored) {
						// structure.dat, tmp files, foreign names: not bodies
					}
				}
			}
		}
		return ids;
	}

	@Override
	public synchronized void saveStructure(String sceneId, byte[] structure) {
		submitWrite(key(sceneId, STRUCTURE_NAME), sceneDir(sceneId),
				new File(sceneDir(sceneId), STRUCTURE_NAME), structure);
	}

	@Override
	public synchronized byte[] loadStructure(String sceneId) {
		joinPending(key(sceneId, STRUCTURE_NAME));
		return readFile(new File(sceneDir(sceneId), STRUCTURE_NAME));
	}

	@Override
	public synchronized void flush() {
		flushLocked();
	}

	private void flushLocked() {
		List<String> keys = new ArrayList<String>(pendingSaves.keySet());
		for (String key : keys) {
			joinPending(key);
		}
	}

	/** Flush and stop the writer thread (server stop / test teardown). */
	public synchronized void close() {
		flushLocked();
		writer.shutdown();
	}
}
