/**
 * 
 */
package org.apache.nutch.parse.url;

import org.apache.nutch.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * @author sihai
 *
 */
public class JingdongItemIndexProcessor extends AbstractItemIndexProcessor {

	public static final Logger LOG = LoggerFactory.getLogger("org.apache.nutch.parse.url");
	
	
	@Override
	protected String getItemId(String url) {
		String itemId = null;
		if(!StringUtil.isEmpty(url)) {
			int index = url.indexOf("/product/");
			int index2 = url.indexOf(".html");
			if(-1 != index && -1 != index2) {
				itemId = url.substring(index + 9, index2);
			}
		}
		return itemId;
	}

	protected void process(String itemId) {
		LOG.info("Process one item in Jingdong, itemId:%s", itemId);
	}
	
	public static void main(String[] args) {
		JingdongItemIndexProcessor processor = new JingdongItemIndexProcessor();
		processor.indexItem("http://www.360buy.com/product/123456.html");
	}
}
