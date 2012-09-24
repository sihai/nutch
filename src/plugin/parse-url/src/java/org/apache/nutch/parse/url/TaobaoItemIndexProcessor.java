/**
 * 
 */
package org.apache.nutch.parse.url;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import org.apache.nutch.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ihome.matrix.dao.exception.ValidateException;
import com.ihome.matrix.domain.CategoryDO;
import com.ihome.matrix.domain.ItemDO;
import com.ihome.matrix.domain.ShopDO;
import com.ihome.matrix.enums.FreightFeePayerEnum;
import com.ihome.matrix.enums.ItemStatusEnum;
import com.ihome.matrix.enums.PlatformEnum;
import com.ihome.matrix.enums.ShopStatusEnum;
import com.ihome.matrix.enums.StuffStatusEnum;
import com.mysql.jdbc.StringUtils;
import com.taobao.api.ApiException;
import com.taobao.api.DefaultTaobaoClient;
import com.taobao.api.TaobaoClient;
import com.taobao.api.domain.Item;
import com.taobao.api.domain.Location;
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
	
	public static final Logger logger = LoggerFactory.getLogger("org.apache.nutch.parse.url");
	public static final String PARMATER_ITEM_ID = "id";
	public static final String PARAMETER_MALL_ST_ITEM_ID = "mallstItemId";
	
	private static final String GATEWAY = "http://gw.api.taobao.com/router/rest";
	private static final String APP_KEY = "12553640";
    private static final String SECRET = "de463fd7cc82a51b060ffe6a11e345f9";

    private static final TaobaoClient client = new DefaultTaobaoClient(GATEWAY, APP_KEY, SECRET);
    
    // lock
    private static final int MAX_LOCK = 64;
    private static Map<Long, Object> itemLockMap;
    private static Map<Long, Object> shopLockMap;
    
    static {
    	itemLockMap = new HashMap<Long, Object>();
    	shopLockMap = new HashMap<Long, Object>();
    	
    	for(int i = 0; i < MAX_LOCK; i++) {
    		itemLockMap.put(Long.valueOf(i), new Object());
    		shopLockMap.put(Long.valueOf(i), new Object());
    	}
    }
	
	@Override
	protected String getItemId(String url) {
		String itemId = getParameter(url, PARMATER_ITEM_ID);
		if(null == itemId) {
			itemId = getParameter(url, PARAMETER_MALL_ST_ITEM_ID);
		}
		return itemId;
	}

	private String getParameter(String strURL, String parameter) {
		
		if(StringUtil.isEmpty(strURL)) {
			return null;
		}
		try {
			URL url = new URL(strURL);
			String queryString = url.getQuery();
			if(StringUtil.isEmpty(strURL)) {
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
		} catch (MalformedURLException e) {
			logger.error(String.format("Wrong url:%s", strURL), e);
		}
		
		return null;
	}
	
	protected void process(String itemId) {
		logger.info(String.format("Process one item in Taobao, itemId:%s", itemId));
		Item item = getItem(Long.valueOf(itemId.trim()));
		if(null != item) {
			Shop shop = getShop(item.getNick());
			sync(item, shop);
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
			logger.info(String.format("Get one item from Taobao", item));
			return item;
		} catch (ApiException e) {
			logger.error(String.format("Get item info from Taobao failed, itemId:%s", itemId), e);
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
			logger.error(String.format("Get shop info from Taobao failed, sellerNick:%s", sellerNick), e);
		}
		
		return null;
	}
	
	/**
	 * 
	 * @param item
	 * @param shop
	 */
	private void sync(Item item, Shop shop) {
		// Shop
		ShopDO igoShop = syncShop(shop);
		if(null != igoShop) {
			syncItem(item, igoShop);
		}
	}
	
	/**
	 * 
	 * @param shop
	 * @return
	 */
	private ShopDO syncShop(Shop shop) {
		Long shopId = shop.getSid();
		synchronized(shopLockMap.get(shopId % MAX_LOCK)) {
			ShopDO igoShop = shopManager.getByShopIdAndPlatform(shopId.toString(), PlatformEnum.PLATFORM_TAOBAO.getValue());
			if(null == igoShop) {
				igoShop = new ShopDO();
			}
			igoShop.setBulletin(shop.getBulletin());
			igoShop.setCategory(null);
			igoShop.setDescription(shop.getDesc());
			igoShop.setShopId(shop.getSid().toString());
			igoShop.setDetailURL("shop" + shopId + ".taobao.com");
			igoShop.setIsDeleted(false);
			igoShop.setName(shop.getTitle());
			igoShop.setPicturePath("http://logo.taobao.com/" + shop.getPicPath());
			igoShop.setPlatform(PlatformEnum.PLATFORM_TAOBAO.getValue());
			igoShop.setSellerName(shop.getNick());
			igoShop.setStatus(ShopStatusEnum.SHOP_STATUS_NORMAL.getValue());
			
			try {
				if(null == igoShop.getId()) {
					shopManager.add(igoShop);
				} else {
					shopManager.update(igoShop);
				}
				return igoShop;
			} catch (ValidateException e) {
				logger.error("Not possiable, sync shop data from taobao failed, exception", e);
				return null;
			}
		}
	}
	
	/**
	 * 
	 * @param item
	 * @param shop
	 */
	private void syncItem(Item item, ShopDO shop) {
		Long itemId = item.getNumIid();
		synchronized(itemLockMap.get(itemId % MAX_LOCK)) {
			ItemDO igoItem = itemManager.getByItemIdAndPlatform(itemId.toString(), PlatformEnum.PLATFORM_TAOBAO.getValue());
			
			if(null == igoItem) {
				igoItem = new ItemDO();
			}
			
			CategoryDO category = new CategoryDO();
			category.setId(1L);
			// FIXME
			igoItem.setCategory(category);
			igoItem.setDetailURL(item.getDetailUrl());
			igoItem.setPostFee(Double.valueOf(item.getPostFee()));
			igoItem.setEmsFee(Double.valueOf(item.getEmsFee()));
			igoItem.setExpressFee(Double.valueOf(item.getExpressFee()));
			FreightFeePayerEnum f = FreightFeePayerEnum.valueOf4Taobao(item.getFreightPayer());
			if(null != f) {
				igoItem.setFreightFeePayer(f.getValue());
			}
			igoItem.setHasDiscount(item.getHasDiscount());
			igoItem.setHasInvoice(item.getHasInvoice());
			igoItem.setHasWarranty(item.getHasWarranty());
			//igoItem.setIsSecondKill(item.getSecondKill());
			igoItem.setIsSellPromise(item.getSellPromise());
			igoItem.setIsXinpin(item.getIsXinpin());
			igoItem.setItemId(itemId.toString());
			Location l = item.getLocation();
			if(null != l) {
				igoItem.setLocation(String.format("%s,%s,%s,%s,%s,%s", l.getCountry(), l.getState(), l.getCity(), l.getDistrict(), l.getAddress(), l.getZip()));
			}
			igoItem.setLogoURL(item.getPicUrl());
			igoItem.setName(item.getTitle());
			igoItem.setNumber(item.getNum());
			igoItem.setPlatform(PlatformEnum.PLATFORM_TAOBAO.getValue());
			igoItem.setPrice(Double.valueOf(item.getPrice()));
			igoItem.setShop(shop);
			igoItem.setStatus(ItemStatusEnum.ITEM_STATUS_ON_SALE.getValue());
			StuffStatusEnum status = StuffStatusEnum.valueOf4Taobao(item.getStuffStatus());
			if(null == status) {
				status = StuffStatusEnum.STUFF_NEW;
			}
			igoItem.setStuffStatus(status.getValue());
			igoItem.setIsDeleted(false);
			try {
				if(null == igoItem.getId()) {
					itemManager.add(igoItem);
				} else {
					itemManager.update(igoItem);
				}
			} catch (ValidateException e) {
				logger.error("Not possiable, sync item data from taobao failed, exception", e);
			}
		}
	}
	
	public static void main(String[] args) {
		TaobaoItemIndexProcessor processor = new TaobaoItemIndexProcessor();
		processor.init();
		processor.indexItem("http://detail.tmall.com/item.htm?id=16872232278&is_b=1&cat_id=50022738&q=&rn=8a0eea4921157f8641c0589261736951");
	}
}
