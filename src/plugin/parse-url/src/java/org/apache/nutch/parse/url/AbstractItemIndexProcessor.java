package org.apache.nutch.parse.url;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractItemIndexProcessor implements ItemIndexProcessor {

	public static final Logger LOG = LoggerFactory.getLogger("org.apache.nutch.parse.url");
	
	@Override
	public void indexItem(String url) {
		LOG.info(String.format("Index one item, url:%s", url));
		String itemId = getItemId(url);
		if(null != itemId) {
			process(itemId);
		} else  {
			LOG.info(String.format("Ignore, url:%s", url));
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
