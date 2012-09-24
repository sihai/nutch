/**
 * 
 */
package org.apache.nutch.parse.url;

/**
 * 
 * @author sihai
 *
 */
public interface ItemIndexProcessor {
	
	String ITEM_MANAGER = "itemManager";			// bean name for itemManager
	String SHOP_MANAGER = "shopManager";			// bean name for shopManager
	String CATEGORY_MANAGER = "categoryManager";	// bean name for categoryManager
	
	void init();
	
	/**
	 * 
	 * @param url
	 */
	void indexItem(String url);
}
