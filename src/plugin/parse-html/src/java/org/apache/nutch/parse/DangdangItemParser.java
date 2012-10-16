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
 * 解析当当商品页面
 * @author sihai
 *
 */
public class DangdangItemParser extends AbstractItemParser {
	
	private static final Log logger = LogFactory.getLog(DangdangItemParser.class);
	private static final Pattern DANGDANG_ITEM_URL_PATTERN= Pattern.compile("^http://product.dangdang.com/product.aspx\\?product_id=(\\S)*");
	
	@Override
	protected boolean accept(String strURL) {
		return DANGDANG_ITEM_URL_PATTERN.matcher(strURL).matches();
	}

	@Override
	protected ItemDO doParse(Content content) {
		return DOMContentUtils.getDangdangItem(content);
	}
	
	public static void main(String[] args) {
		try {
			ItemParser parser = new DangdangItemParser();
			String strURL = "http://product.dangdang.com/product.aspx?product_id=1014060112&spm=123444&_xx_=123456";
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
