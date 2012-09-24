package org.apache.nutch.parse.url;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.ihome.matrix.manager.CategoryManager;
import com.ihome.matrix.manager.ItemManager;
import com.ihome.matrix.manager.ShopManager;

public abstract class AbstractItemIndexProcessor implements ItemIndexProcessor {

	public static final Logger logger = LoggerFactory.getLogger("org.apache.nutch.parse.url");
	
	protected ApplicationContext context;		//
	protected ShopManager shopManager;			//
	protected ItemManager itemManager;			//
	protected CategoryManager categoryManager;	//
	
	public void init() {
		context = new ClassPathXmlApplicationContext("classpath:/spring/spring-matrix.xml");
		shopManager = (ShopManager)context.getBean(SHOP_MANAGER);
		itemManager = (ItemManager)context.getBean(ITEM_MANAGER);
		categoryManager = (CategoryManager)context.getBean(CATEGORY_MANAGER);
	}
	
	@Override
	public void indexItem(String url) {
		logger.info(String.format("Index one item, url:%s", url));
		String itemId = getItemId(url);
		if(null != itemId) {
			process(itemId);
		} else  {
			logger.info(String.format("Ignore, url:%s", url));
		}
	}
	
	/**
	 * 
	 * @param itemId
	 */
	protected abstract void process(String itemId);
	
	/**
	 * 
	 * @param url
	 * @return
	 */
	protected abstract String getItemId(String url);
}
