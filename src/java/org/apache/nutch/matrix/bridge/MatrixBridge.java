package org.apache.nutch.matrix.bridge;

import java.util.HashMap;
import java.util.Map;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.ihome.matrix.domain.ShopDO;
import com.ihome.matrix.enums.PlatformEnum;
import com.ihome.matrix.enums.ShopStatusEnum;
import com.ihome.matrix.manager.CategoryManager;
import com.ihome.matrix.manager.ItemManager;
import com.ihome.matrix.manager.ShopManager;

/**
 * 
 * @author sihai
 *
 */
public class MatrixBridge {
	
	private static final String ITEM_MANAGER = "itemManager";			// bean name for itemManager
	private static final String SHOP_MANAGER = "shopManager";			// bean name for shopManager
	private static final String CATEGORY_MANAGER = "categoryManager";	// bean name for categoryManager
	
	private static ApplicationContext context;		//
	private static ShopManager shopManager;			//
	private static ItemManager itemManager;			//
	private static CategoryManager categoryManager;	//
	
	private static Map<PlatformEnum, ShopDO> fixedShopMap;
	
	static {
		context = new ClassPathXmlApplicationContext("classpath:/spring/spring-matrix.xml");
		shopManager = (ShopManager)context.getBean(SHOP_MANAGER);
		itemManager = (ItemManager)context.getBean(ITEM_MANAGER);
		categoryManager = (CategoryManager)context.getBean(CATEGORY_MANAGER);
		
		fixedShopMap = new HashMap<PlatformEnum, ShopDO>(PlatformEnum.values().length);
		for(PlatformEnum platform : PlatformEnum.values()) {
			ShopDO shop = new ShopDO();
			shop.setId(Long.valueOf(platform.getValue()));
			shop.setPlatform(platform.getValue());
			shop.setShopId(platform.getName());
			shop.setName(platform.getName());
			shop.setSellerName(platform.getName());
			shop.setDetailURL(platform.getURL());
			shop.setStatus(ShopStatusEnum.SHOP_STATUS_NORMAL.getValue());
			shop.setIsDeleted(false);
			fixedShopMap.put(platform, shop);
		}
	}
	
	public static ShopManager getShopManager() {
		return shopManager;
	}
	
	public static ItemManager getItemManager() {
		return itemManager;
	}
	
	public static CategoryManager getCategoryManager() {
		return categoryManager;
	}
	
	public static ShopDO getFixedShop(PlatformEnum platform) {
		return fixedShopMap.get(platform);
	}
}
