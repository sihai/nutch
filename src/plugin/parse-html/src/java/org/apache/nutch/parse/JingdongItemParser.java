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
public class JingdongItemParser extends AbstractItemParser {
	
	private static final Log logger = LogFactory.getLog(JingdongItemParser.class);
	private static final Pattern JINGDONG_ITEM_URL_PATTERN= Pattern.compile("^http://www.360buy.com/product/(\\S)*\\.html(\\S)*");
	
	@Override
	protected boolean accept(String strURL) {
		return JINGDONG_ITEM_URL_PATTERN.matcher(strURL).matches();
	}

	@Override
	protected ItemDO doParse(Content content) {
		ItemDO item = DOMContentUtils.getJingdongItemV2(content);
		System.out.println(item);
		return item;
	}
	
	public static void main(String[] args) {
		
		try {
			ItemParser parser = new JingdongItemParser();
			String strURL = "http://www.360buy.com/product/717554.html";
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
