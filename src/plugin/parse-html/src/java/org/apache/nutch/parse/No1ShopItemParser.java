package org.apache.nutch.parse;

import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.nutch.crawl.CrawlDatum;
import org.apache.nutch.parse.html.DOMContentUtils;
import org.apache.nutch.protocol.Content;
import org.apache.nutch.protocol.Protocol;
import org.apache.nutch.protocol.ProtocolFactory;
import org.apache.nutch.protocol.ProtocolNotFound;
import org.apache.nutch.protocol.ProtocolOutput;
import org.apache.nutch.util.NutchConfiguration;

import com.ihome.matrix.domain.ItemDO;

/**
 * 解析一号店商品页面
 * @author sihai
 *
 */
public class No1ShopItemParser extends AbstractItemParser {
	
	private static final Log logger = LogFactory.getLog(No1ShopItemParser.class);
	
	private static final Pattern NO1_SHOP_ITEM_URL_PATTERN= Pattern.compile("^http://www.yihaodian.com/product/(\\S)*");
	
	@Override
	protected boolean accept(String strURL) {
		return NO1_SHOP_ITEM_URL_PATTERN.matcher(strURL).matches();
	}

	@Override
	protected ItemDO doParse(Content content) {
		return DOMContentUtils.getNo1ShopItem(content);
	}
	
	public static void main(String[] args) {
		
		try {
			ItemParser parser = new No1ShopItemParser();
			String strURL = "http://www.yihaodian.com/product/3833859_1";
			Configuration conf = NutchConfiguration.create();
			ProtocolFactory protocolFactory = new ProtocolFactory(conf);
			Protocol protocol = protocolFactory.getProtocol(strURL);
			ProtocolOutput output = protocol.getProtocolOutput(new Text(strURL), new CrawlDatum());
			parser.parse(output.getContent());
		} catch (ProtocolNotFound e) {
			logger.error(e);
		} finally {
		}
	}
}
