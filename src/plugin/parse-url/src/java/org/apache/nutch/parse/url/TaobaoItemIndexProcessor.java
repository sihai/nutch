/**
 * 
 */
package org.apache.nutch.parse.url;

import org.apache.nutch.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.taobao.api.ApiException;
import com.taobao.api.DefaultTaobaoClient;
import com.taobao.api.TaobaoClient;
import com.taobao.api.domain.Item;
import com.taobao.api.domain.Shop;
import com.taobao.api.request.ItemGetRequest;
import com.taobao.api.request.ShopGetRequest;
import com.taobao.api.response.ItemGetResponse;
import com.taobao.api.response.ShopGetResponse;

/**
 * 
 * @author sihai
 *
 */
public class TaobaoItemIndexProcessor extends AbstractItemIndexProcessor {
	
	public static final Logger LOG = LoggerFactory.getLogger("org.apache.nutch.parse.url");
	public static final String PARMATER_ITEM_ID = "id";
	
	 private static final String GATEWAY = "http://gw.api.taobao.com/router/rest";
	private static final String APP_KEY = "12553640";
    private static final String SECRET = "de463fd7cc82a51b060ffe6a11e345f9";

    private static final TaobaoClient client = new DefaultTaobaoClient(GATEWAY, APP_KEY, SECRET);
	
	
	@Override
	protected String getItemId(String url) {
		return getParameter(url, PARMATER_ITEM_ID);
	}

	private String getParameter(String queryString, String parameter) {
		if(StringUtil.isEmpty(queryString)) {
			return null;
		}
		String[] kvs = queryString.split("&");
		String[] kv = null;
		for(String s : kvs) {
			if(StringUtil.isEmpty(s)) {
				continue;
			} else {
				kv = s.split("=");
				if(kv.length == 2) {
					if(kv[0].equals(parameter)) {
						return kv[1];
					}
				}
			}
		}
		return null;
	}
	
	protected void process(String itemId) {
		LOG.info(String.format("Process one item in Taobao, itemId:%s", itemId));
		Item item = getItem(Long.valueOf(itemId.trim()));
		if(null != item) {
			Shop shop = getShop(item.getNick());
		}
	}
	
	private Item getItem(Long itemId) {
		ItemGetRequest request = new ItemGetRequest();
		request.setFields("detail_url,num_iid,title,nick,type,"
				+ "desc,skus,props_name,created,"
				+ "is_lightning_consignment,is_fenxiao,auction_point,after_sale_id,"
				+ "is_xinpin,global_stock_type,cid,props,pic_url,num,stuff_status,"
				+ "location,price,post_fee,express_fee,ems_fee,has_discount,freight_payer,"
				+ "has_invoice,has_warranty,postage_id,product_id,item_imgs,prop_imgs,is_virtual,"
				+ "videos,is_3D,one_station,second_kill,auto_fill,violation,wap_detail_url,cod_postage_id,sell_promise");
		request.setNumIid(Long.valueOf(itemId));
		//request.setTrackIid("123_track_456");
		try {
			ItemGetResponse response = client.execute(request);
			Item item = response.getItem();
			LOG.info(String.format("Get one item from Taobao", item));
			return item;
		} catch (ApiException e) {
			LOG.error(String.format("Get item info from Taobao failed, itemId:%s", itemId), e);
		}
		return null;
	}
	
	private Shop getShop(String sellerNick) {
		
		ShopGetRequest req = new ShopGetRequest();
		req.setFields("sid,cid,title,nick,desc,bulletin,pic_path,created,modified");
		req.setNick(sellerNick);
		try {
			ShopGetResponse response = client.execute(req);
			return response.getShop();
		} catch (ApiException e) {
			LOG.error(String.format("Get shop info from Taobao failed, sellerNick:%s", sellerNick), e);
		}
		
		return null;
	}
	
	public static void main(String[] args) {
		TaobaoItemIndexProcessor processor = new TaobaoItemIndexProcessor();
		processor.indexItem("http://detail.tmall.com/item.htm?spm=3.259685.255205.45&id=15593997615");
	}
}
