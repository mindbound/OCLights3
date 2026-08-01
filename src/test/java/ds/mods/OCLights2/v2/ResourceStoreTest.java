package ds.mods.OCLights2.v2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ds.mods.OCLights2.v2.persist.DirectoryResourceStore;

public class ResourceStoreTest {

	private File root;
	private DirectoryResourceStore store;

	@Before
	public void setUp() throws Exception {
		root = File.createTempFile("ocl3-store-test", "");
		assertTrue(root.delete());
		assertTrue(root.mkdirs());
		store = new DirectoryResourceStore(root);
	}

	@After
	public void tearDown() {
		store.close();
		deleteRecursively(root);
	}

	private static void deleteRecursively(File file) {
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				deleteRecursively(child);
			}
		}
		file.delete();
	}

	private static byte[] pattern(int len, int seed) {
		byte[] data = new byte[len];
		for (int i = 0; i < len; i++) {
			data[i] = (byte) (i * 31 + seed);
		}
		return data;
	}

	@Test
	public void saveThenLoadRoundTrips() {
		byte[] data = pattern(4096, 1);
		store.save("scene-a", 7, data);
		// Load must observe the async in-flight write (the SaveHandler contract).
		assertArrayEquals(data, store.load("scene-a", 7));
	}

	@Test
	public void overwriteReplacesTheBody() {
		store.save("scene-a", 7, pattern(64, 1));
		store.save("scene-a", 7, pattern(64, 2));
		assertArrayEquals(pattern(64, 2), store.load("scene-a", 7));
	}

	@Test
	public void missingBodyLoadsNull() {
		assertNull(store.load("scene-a", 99));
	}

	@Test
	public void deleteRemovesTheBody() {
		store.save("scene-a", 7, pattern(64, 1));
		store.delete("scene-a", 7);
		assertNull(store.load("scene-a", 7));
	}

	@Test
	public void scenesAreIsolated() {
		store.save("scene-a", 7, pattern(64, 1));
		store.save("scene-b", 7, pattern(64, 2));
		assertArrayEquals(pattern(64, 1), store.load("scene-a", 7));
		assertArrayEquals(pattern(64, 2), store.load("scene-b", 7));
		store.deleteScene("scene-a");
		assertNull(store.load("scene-a", 7));
		assertArrayEquals(pattern(64, 2), store.load("scene-b", 7));
	}

	@Test
	public void listResourcesReflectsContents() {
		store.save("scene-a", 3, pattern(16, 1));
		store.save("scene-a", 11, pattern(16, 2));
		List<Integer> ids = store.listResources("scene-a");
		assertEquals(2, ids.size());
		assertTrue(ids.contains(3));
		assertTrue(ids.contains(11));
		assertTrue(store.listResources("scene-never").isEmpty());
	}

	@Test
	public void sceneIdsAreSanitizedForTheFilesystem() {
		String hostile = "../..\\evil:scene?<>|";
		store.save(hostile, 1, pattern(16, 1));
		assertArrayEquals(pattern(16, 1), store.load(hostile, 1));
		// Everything stays under the root — no traversal.
		assertEquals(1, root.listFiles().length);
		assertTrue(root.listFiles()[0].getName().indexOf("..") != 0);
	}

	@Test
	public void containsReflectsStoredAndPendingBodies() {
		assertTrue(!store.contains("scene-a", 7));
		store.save("scene-a", 7, pattern(64, 1));
		assertTrue(store.contains("scene-a", 7)); // pending or stored: both count
		store.flush();
		assertTrue(store.contains("scene-a", 7));
		store.delete("scene-a", 7);
		assertTrue(!store.contains("scene-a", 7));
	}

	@Test
	public void structureSlotIsSeparateFromBodies() {
		store.saveStructure("scene-a", pattern(128, 3));
		store.save("scene-a", 1, pattern(16, 1));
		assertArrayEquals(pattern(128, 3), store.loadStructure("scene-a"));
		assertEquals(1, store.listResources("scene-a").size()); // structure.dat not listed
		store.deleteScene("scene-a");
		assertNull(store.loadStructure("scene-a"));
	}

	@Test
	public void flushJoinsAllPendingWrites() {
		for (int i = 0; i < 16; i++) {
			store.save("scene-a", i, pattern(2048, i));
		}
		store.flush();
		// Directory names carry a hash suffix (injective sanitize), so verify through the
		// store API rather than raw paths.
		for (int i = 0; i < 16; i++) {
			assertTrue(store.contains("scene-a", i));
			assertArrayEquals(pattern(2048, i), store.load("scene-a", i));
		}
	}
}
