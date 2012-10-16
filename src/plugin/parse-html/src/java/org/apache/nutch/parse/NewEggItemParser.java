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
 * 解析新蛋商品页面
 * @author sihai
 *
 */
public class NewEggItemParser extends AbstractItemParser {
	
	private static final Log logger = LogFactory.getLog(NewEggItemParser.class);
	private static final Pattern NEW_EGG_ITEM_URL_PATTERN= Pattern.compile("^http://www.newegg.com.cn/Product/(\\S)*.htm(\\S)*");
	
	@Override
	protected boolean accept(String strURL) {
		return NEW_EGG_ITEM_URL_PATTERN.matcher(strURL).matches();
	}

	@Override
	protected ItemDO doParse(Content content) {
		return DOMContentUtils.getNewEggItem(content);
	}
	
	public static void main(String[] args) {
		try {
			ItemParser parser = new NewEggItemParser();
			String strURL = "http://www.newegg.com.cn/Product/A26-032-1R0-03.htm?cm_sp=ProductRank-_-A26-032-1R0-03-_-product";
			Configuration conf = NutchConfiguration.create();
			ProtocolFactory protocolFactory = new ProtocolFactory(conf);
			Protocol protocol = protocolFactory.getProtocol(strURL);
			ProtocolOutput output = protocol.getProtocolOutput(new Text(strURL), new CrawlDatum());
			parser.parse(output.getContent());
			
			strURL = "http://www.newegg.com.cn/Product/A41-299-2AE.htm?cm_sp=HotSell-_-A41-299-2AE-_-product";
			protocol = protocolFactory.getProtocol(strURL);
			output = protocol.getProtocolOutput(new Text(strURL), new CrawlDatum());
			parser.parse(output.getContent());
		} catch (ProtocolNotFound e) {
			logger.error(e);
		} finally {
		}
	}
}
