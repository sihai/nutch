package org.apache.nutch.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.nutch.crawl.CrawlDatum;
import org.apache.nutch.protocol.Content;
import org.apache.nutch.protocol.Protocol;
import org.apache.nutch.protocol.ProtocolFactory;
import org.apache.nutch.protocol.ProtocolNotFound;
import org.apache.nutch.protocol.ProtocolOutput;
import org.apache.nutch.util.NutchConfiguration;
import org.apache.nutch.util.NutchThreadFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.ihome.matrix.enums.PlatformEnum;

/**
 * 
 * @author sihai
 *
 */
public class ItemParserHelper {
	
	private static final Log logger = LogFactory.getLog(ItemParserHelper.class);
	
	public static final int DEFAULT_MIN_THREAD = 4;
	public static int DEFAULT_MAX_THREAD = 32;
	public static int DEFAULT_MAX_WORK_QUEUE_SIZE = 2048;
	public static long MAX_KEEP_ALIVE_TIME = 60;
	  
	private static int minThread = DEFAULT_MIN_THREAD;
	private static int maxThread = DEFAULT_MAX_THREAD;
	private static int workQueueSize = DEFAULT_MAX_WORK_QUEUE_SIZE;
	private static long keepAliveTime = MAX_KEEP_ALIVE_TIME;							// s
	  
	private static BlockingQueue<Runnable> workQueue;	//
	private static ThreadPoolExecutor threadPool;
	  
	private static Object _threadPool_lock_ = new Object();
	private static boolean _threadPool_inited_ = false;
	
	private List<ItemParser> itemParserList;
	
	public void init(Configuration conf) {
		
		// thread pool
		synchronized(_threadPool_lock_) {
	    	if(!_threadPool_inited_) {
			    minThread = conf.getInt("parser.minThread", DEFAULT_MIN_THREAD);
			    maxThread = conf.getInt("parser.maxThread", DEFAULT_MAX_THREAD);
			    workQueueSize = conf.getInt("parser.workQueueSize", DEFAULT_MAX_WORK_QUEUE_SIZE);
			    keepAliveTime = conf.getLong("parser.keepAliveTime", MAX_KEEP_ALIVE_TIME);
			    workQueue = new LinkedBlockingQueue<Runnable>(workQueueSize);
			    threadPool = new ThreadPoolExecutor(minThread, maxThread,
			            keepAliveTime, TimeUnit.SECONDS,
			            workQueue, new NutchThreadFactory("HTML-Parser", null, true));
			    _threadPool_inited_ = true;
	    	}
		}
	    
	    //
	    itemParserList = new ArrayList<ItemParser>(PlatformEnum.values().length - 1);
	    itemParserList.add(new AmazonItemParser());
	    itemParserList.add(new Coo8ItemParser());
	    itemParserList.add(new DangdangItemParser());
	    itemParserList.add(new EfeihuItemParser());
	    itemParserList.add(new Five1BuyItemParser());
	    itemParserList.add(new GomeItemParser());
	    itemParserList.add(new JingdongItemParser());
	    itemParserList.add(new LusenItemParser());
	    itemParserList.add(new NewEggItemParser());
	    itemParserList.add(new No1ShopItemParser());
	    itemParserList.add(new SuningItemParser());
	    itemParserList.add(new New7ItemParser());
	    itemParserList.add(new OukuItemParser());
	    itemParserList.add(new RedBabyItemParser());
	    
	}
	
	public void stop() {
		threadPool.shutdown();
	}
	
	/**
	 * 
	 * @param content
	 */
	public void parse(Content content)  {
		logger.warn("ItemParser.threadPool:");
		logger.warn(String.format("corePoolSize:%d\n" +
	    		"maximumPoolSize:%d\n" +
	    		"activeCount:%d\n" +
	    		"poolSize:%d\n" +
	    		"workQueueSize:%d\n" +
	    		"workQueueRemainingCapacity:%d", 
	    		threadPool.getCorePoolSize(), 
	    		threadPool.getMaximumPoolSize(), 
	    		threadPool.getActiveCount(), 
	    		threadPool.getPoolSize(),
	    		threadPool.getQueue().size(),
	    		threadPool.getQueue().remainingCapacity()));
		threadPool.execute(new ParseTask(content));
	}
	
	/**
	 * 
	 * @author sihai
	 *
	 */
	private class ParseTask implements Runnable {

		private Content content;
		
		public ParseTask(Content content) {
			this.content = content;
		}
		
		@Override
		public void run() {
			for(ItemParser parser : itemParserList) {
				try {
					parser.parse(content);
				} catch (Throwable t) {
					t.printStackTrace();
					logger.error(t);
				}
			}
			
		}
	}
	
	public static void main(String[] args) {
		
		ItemParserHelper helper = null;
		try {
			String[] strURLs = new String[]{"http://www.amazon.cn/gp/product/B008HXD9KA/ref=s9_simh_gw_p147_d0_i4?pf_rd_m=A1AJ19PSB66TGU&pf_rd_s=center-2&pf_rd_r=08HJFQ2H6YT1VX2HPZRR&pf_rd_t=101&pf_rd_p=58223152&pf_rd_i=899254051",
											"http://www.coo8.com/product/358495.html",
											"http://product.dangdang.com/product.aspx?product_id=1014060112&spm=123444&_xx_=123456",
											"http://www.efeihu.com/Product/2020101019082.html?pcid=hp6-1-1",
											"http://item.51buy.com/item-107316.html?YTAG=1.100040000",
											"http://www.gome.com.cn/ec/homeus/jump/product/9110530556.html?jkjlkj=jlkjlkdsjf&fdsfdsfjl=xjlsdjfsldkfj",
											"http://www.360buy.com/product/717554.html",
											"http://www.lusen.com/Product/ProductInfo.aspx?id=2429&cruxId=38&Type=PanicBuy",
											"http://www.new7.com/product/114752.html#F2",
											"http://www.newegg.com.cn/Product/A41-299-2AE.htm?cm_sp=HotSell-_-A41-299-2AE-_-product",
											"http://www.yihaodian.com/product/3833859_1",
											"http://www.ouku.com/goods57133/?jkjlsjlj=kjflkdsjflkdsf&jlkdsjfslkdf=jlkjdsflksd",
											"http://www.redbaby.com.cn/yongpin/10805101238040.html",
											"http://www.suning.com/emall/prd_10052_10051_-7_1350461_.html",
											"http://www.tao3c.com/product/503451.html?jkjlsjlj=kjflkdsjflkdsf&jlkdsjfslkdf=jlkjdsflksd"
					};
			Configuration conf = NutchConfiguration.create();
			helper = new ItemParserHelper();
			helper.init(conf);
			ProtocolFactory protocolFactory = new ProtocolFactory(conf);
			Protocol protocol = protocolFactory.getProtocol("http://www.google.com");
			ProtocolOutput output = null;
			for(String strURL : strURLs) {
				output = protocol.getProtocolOutput(new Text(strURL), new CrawlDatum());
				helper.parse(output.getContent());
			}
			Thread.sleep(10 * 60 * 1000);
		} catch (ProtocolNotFound e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} finally {
			if(null != helper) {
				helper.stop();
			}
		}
	}
}
