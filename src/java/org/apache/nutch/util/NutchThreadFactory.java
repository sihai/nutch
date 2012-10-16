package org.apache.nutch.util;

import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p>
 * 线程工厂类
 * </p>
 * @author sihai
 *
 */
public class NutchThreadFactory implements ThreadFactory {

	private String prefix;			// 线程名前缀
	private ThreadGroup group;				// 线程组
	private boolean  isDaemon;				// 是否设置为精灵线程
	private AtomicInteger tNo;				// 线程编号, 线程名的一部分

	public NutchThreadFactory(String prefix) {
		this(prefix, null, false);
	}

	public NutchThreadFactory(String prefix, ThreadGroup group, boolean isDaemon) {
		tNo = new AtomicInteger(0);
		this.prefix = prefix;
		if(null != group) {
			this.group = group;
		} else {
			SecurityManager sm = System.getSecurityManager();
			group = (sm != null) ? sm.getThreadGroup() : Thread.currentThread().getThreadGroup();
		}
		this.isDaemon = isDaemon;
	}

	@Override
	public Thread newThread(Runnable r) {
		Thread t = new Thread(group, r, String.format("%s-Thread-%d", prefix, tNo.incrementAndGet()));
		t.setDaemon(isDaemon);
		/*if (t.getPriority() != Thread.NORM_PRIORITY) {
			t.setPriority(Thread.NORM_PRIORITY);
		}*/
		return t;
	}

	/**
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		
		try {
			Thread.sleep(1000 * 30);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		final ThreadPoolExecutor threadPool = new ThreadPoolExecutor(2, 16,
		            60, TimeUnit.SECONDS,
		            new LinkedBlockingQueue<Runnable>(16), new NutchThreadFactory("HTML-Parser", null, true));
		    //executorService = Executors.newCachedThreadPool(new ThreadFactoryBuilder().setNameFormat("parse-%d").setDaemon(true).build());
		for(int i = 0; i < 15; i++) {
			new Thread(new Runnable() {

				@Override
				public void run() {
					for(int i = 0; i < 10000; i++) {
						for(int j = 0; j < 10000; j++) {
							threadPool.submit(new Task());
						}
						try {
							Thread.sleep(1000);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				}
				
			}).start();
		}
		
		try {
			Thread.sleep(10000000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	public static class Task implements Callable {

		@Override
		public Object call() throws Exception {
			// TODO Auto-generated method stub
			return null;
		}
	}
}
