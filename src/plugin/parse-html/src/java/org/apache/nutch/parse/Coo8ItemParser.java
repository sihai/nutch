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
 * 解析库巴商品页面
 * @author sihai
 *
 */
public class Coo8ItemParser extends AbstractItemParser {
	
	private static final Log logger = LogFactory.getLog(Coo8ItemParser.class);
	private static final Pattern COO8_ITEM_URL_PATTERN= Pattern.compile("^http://www.coo8.com/product/(\\S)*\\.html(\\S)*");
	
	@Override
	protected boolean accept(String strURL) {
		return COO8_ITEM_URL_PATTERN.matcher(strURL).matches();
	}

	@Override
	protected ItemDO doParse(Content content) {
		return DOMContentUtils.getCool8Item(content);
	}
	
	public static void main(String[] args) {
		try {
			ItemParser parser = new Coo8ItemParser();
			String strURL = "http://www.coo8.com/product/358495.html";
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
