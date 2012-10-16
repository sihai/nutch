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
 * 解析亚马逊商品页面
 * @author sihai
 *
 */
public class AmazonItemParser extends AbstractItemParser {
	
	private static final Log logger = LogFactory.getLog(AmazonItemParser.class);
	private static final Pattern AMAZON_ITEM_URL_PATTERN = Pattern.compile("^http://www.amazon.cn/gp/product/(\\S)*");
	private static final Pattern AMZON_ITEM_URL_PATTERN_1 = Pattern.compile("^http://http://www.amazon.cn/(\\S)*/dp/(\\S)*");
	
	@Override
	protected boolean accept(String strURL) {
		return AMAZON_ITEM_URL_PATTERN.matcher(strURL).matches() || AMZON_ITEM_URL_PATTERN_1.matcher(strURL).matches();
	}

	@Override
	protected ItemDO doParse(Content content) {
		ItemDO item = DOMContentUtils.getAmazonItem(content);
		//System.out.println(item);
		return item;
	}
	
	public static void main(String[] args) {
	
		try {
			ItemParser parser = new AmazonItemParser();
			String strURL = "http://www.amazon.cn/gp/product/B008HXD9KA/ref=s9_simh_gw_p147_d0_i4?pf_rd_m=A1AJ19PSB66TGU&pf_rd_s=center-2&pf_rd_r=08HJFQ2H6YT1VX2HPZRR&pf_rd_t=101&pf_rd_p=58223152&pf_rd_i=899254051";
			//String strURL = "http://www.amazon.cn/gp/product/B005CSNX54/ref=s9_al_bw_g75_ir02?pf_rd_m=A1AJ19PSB66TGU&pf_rd_s=center-4&pf_rd_r=1KTZ4AW9W5TR1A096WJE&pf_rd_t=101&pf_rd_p=65762492&pf_rd_i=42692071";
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
