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
 * 解析京东商品页面
 * @author sihai
 *
 */
public class Five1BuyItemParser extends AbstractItemParser {
	
	private static final Log logger = LogFactory.getLog(Five1BuyItemParser.class);
	
	private static final Pattern FIVE1_BUY_ITEM_URL_PATTERN= Pattern.compile("^http://item.51buy.com/item-(\\S)*.html(\\S)*");
	
	@Override
	protected boolean accept(String strURL) {
		return FIVE1_BUY_ITEM_URL_PATTERN.matcher(strURL).matches();
	}

	@Override
	protected ItemDO doParse(Content content) {
		return DOMContentUtils.getFive1BuyItem(content);
	}
	
	public static void main(String[] args) {
		
		try {
			ItemParser parser = new Five1BuyItemParser();
			String strURL = "http://item.51buy.com/item-107316.html?YTAG=1.100040000";
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
