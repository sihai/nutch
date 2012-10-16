package org.apache.nutch.parse;

import java.util.HashMap;
import java.util.Map;

import org.apache.nutch.matrix.bridge.MatrixBridge;
import org.apache.nutch.protocol.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ihome.matrix.dao.exception.ValidateException;
import com.ihome.matrix.domain.CategoryDO;
import com.ihome.matrix.domain.ItemDO;
import com.ihome.matrix.domain.ShopDO;
import com.ihome.matrix.enums.PlatformEnum;
import com.ihome.matrix.manager.CategoryManager;
import com.ihome.matrix.manager.ItemManager;
import com.ihome.matrix.manager.ShopManager;

public abstract class AbstractItemParser implements ItemParser {

	public static final Logger logger = LoggerFactory.getLogger(AbstractItemParser.class);
	
	/**
	 * for db access
	 */
	protected ShopManager shopManager = MatrixBridge.getShopManager();
	protected ItemManager itemManager = MatrixBridge.getItemManager();
	protected CategoryManager categoryManager = MatrixBridge.getCategoryManager();
	
	// lock
    private static final int MAX_LOCK = 64;
    private static Map<Integer, Object> itemLockMap;
    private static Map<Integer, Object> shopLockMap;
    
    static {
    	itemLockMap = new HashMap<Integer, Object>();
    	shopLockMap = new HashMap<Integer, Object>();
    	
    	for(int i = 0; i < MAX_LOCK; i++) {
    		itemLockMap.put(Integer.valueOf(i), new Object());
    		shopLockMap.put(Integer.valueOf(i), new Object());
    	}
    }
	
	@Override
	public void parse(Content content) {
		if(accept(content.getUrl())) {
			ItemDO item = doParse(content);
			if(null != item) {
				sync(item);
			}
		}
	}
	
	/**
	 * 
	 * @param item
	 * @param shop
	 */
	private void sync(ItemDO item) {
		// Shop
		//ShopDO igoShop = syncShop(item.getShop());
		//if(null != igoShop) {
			syncShop(item.getShop());
			syncCategory(item.getCategory());
			syncItem(item);
		//}
	}
	
	/**
	 * 
	 * @param shop
	 * @return
	 */
	private ShopDO syncShop(ShopDO shop) {
		String shopId = shop.getShopId();
		int index = shopId.hashCode() % MAX_LOCK;
		index = index < 0 ? -index : index;
		synchronized(shopLockMap.get(index)) {
			ShopDO igoShop = shopManager.getByShopIdAndPlatform(shopId, shop.getPlatform());
			
			try {
				if(null != igoShop) {
					shop.setId(igoShop.getId());
					shopManager.update(shop);
				} else {
					shopManager.add(shop);
				}
				return shop;
			} catch (ValidateException e) {
				logger.error("Not possiable, sync shop data from taobao failed, exception", e);
				return null;
			}
		}
	}
	
	/**
	 * 
	 * @param category
	 */
	private void syncCategory(CategoryDO category) {
		// TODO
	}
	
	/**
	 * 
	 * @param item
	 * @param shop
	 */
	private void syncItem(ItemDO item) {
		String itemId = item.getItemId();
		int index = itemId.hashCode() % MAX_LOCK;
		index = index < 0 ? -index : index;
		synchronized(itemLockMap.get(index)) {
			ItemDO igoItem = itemManager.getByItemIdAndPlatform(itemId.toString(), item.getPlatform());
			
			try {
				if(null != igoItem) {
					item.setId(igoItem.getId());
					itemManager.update(item);
				} else {
					itemManager.add(item);
				}
			} catch (ValidateException e) {
				logger.error("Not possiable, sync item data from taobao failed, exception", e);
			}
		}
	}
	
	/**
	 * 
	 * @param strURL
	 * @return
	 */
	protected abstract boolean accept(String strURL);
	
	/**
	 * 
	 * @param content
	 */
	protected abstract ItemDO doParse(Content content);
}
