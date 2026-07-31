package ds.mods.OCLights2;

import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.WeakHashMap;

import ds.mods.OCLights2.gpu.DrawCMD;
import ds.mods.OCLights2.gpu.GPU;


public class ClientDrawThread extends Thread {
	public WeakHashMap<GPU, Deque<DrawCMD>> draws = new WeakHashMap<GPU, Deque<DrawCMD>>();

	@Override
	public void run() {
		ArrayList<GPU> targets = new ArrayList<GPU>();
		ArrayList<ArrayList<DrawCMD>> batches = new ArrayList<ArrayList<DrawCMD>>();
		while (true)
		{
			// Copy batches out under the locks the packet handler shares, then rasterize
			// after releasing them — the netty thread must never wait on processCommand.
			targets.clear();
			batches.clear();
			synchronized (draws)
			{
				Iterator<Entry<GPU,Deque<DrawCMD>>> iter = draws.entrySet().iterator();
				while (iter.hasNext())
				{
					Entry<GPU,Deque<DrawCMD>> e = iter.next();
					GPU gpu = e.getKey();
					// No monitor yet: leave the commands queued for a later pass.
					if (gpu.currentMonitor == null) continue;
					Deque<DrawCMD> stack = e.getValue();
					synchronized (stack)
					{
						if (stack.isEmpty()) continue;
						batches.add(new ArrayList<DrawCMD>(stack));
						targets.add(gpu);
						stack.clear();
					}
				}
			}
			for (int i = 0; i < targets.size(); i++)
			{
				GPU gpu = targets.get(i);
				ArrayList<DrawCMD> batch = batches.get(i);
				synchronized (gpu)
				{
					if (gpu.currentMonitor == null) continue;
					synchronized (gpu.currentMonitor)
					{
						synchronized (gpu.currentMonitor.tex)
						{
							gpu.currentMonitor.tex.renderLock = true;
							for (DrawCMD d : batch)
							{
								try {
									if (d == null) continue;
									gpu.processCommand(d);
								} catch (Exception e1) {
									OCLights2.debug("Unable to process cmd in clientdrawthread");
								}
							}
							gpu.currentMonitor.tex.texUpdate();
							gpu.currentMonitor.tex.renderLock = false;
							try{
							gpu.currentMonitor.tex.notifyAll();
							}catch(Exception eee){eee.printStackTrace();}
						}
					}
				}
			}
			try {
				Thread.sleep(1L);
			} catch (InterruptedException e) {
				OCLights2.debug("ClientDrawThread is unable to sleep.");
			}
		}
	}

}
