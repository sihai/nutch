/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nutch.parse.url;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.nutch.metadata.Metadata;
import org.apache.nutch.parse.Parse;
import org.apache.nutch.parse.ParseResult;
import org.apache.nutch.parse.ParseStatus;
import org.apache.nutch.parse.Parser;
import org.apache.nutch.protocol.Content;
import org.apache.nutch.util.NutchConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class URLParser implements Parser {
	
	public static final Logger LOG = LoggerFactory.getLogger("org.apache.nutch.parse.url");
	
	private static final Map<String, ItemIndexProcessor> itemIndexProcessorMap = new HashMap<String, ItemIndexProcessor>();
	
	static {
		AbstractItemIndexProcessor processor = new TaobaoItemIndexProcessor();
		processor.init();
		itemIndexProcessorMap.put("www.taobao.com", processor);
		itemIndexProcessorMap.put("www.tmall.com", itemIndexProcessorMap.get("www.taobao.com"));
		itemIndexProcessorMap.put("detail.tmall.com", itemIndexProcessorMap.get("www.taobao.com"));
		itemIndexProcessorMap.put("item.taobao.com", itemIndexProcessorMap.get("taobao.com"));
		itemIndexProcessorMap.put("www.360buy.com", new JingdongItemIndexProcessor());
	}

	private Configuration conf;
  
	public ParseResult getParse(Content content) {

	    try {
	    	String strURL = content.getUrl();
	    	LOG.warn(String.format("Try to parse url: %s", strURL));
	    	URL url = new URL(strURL);
	    	String host = url.getHost();
	    	ItemIndexProcessor processor = itemIndexProcessorMap.get(host);
	    	if(null == processor) {
	    		LOG.warn(String.format("None ItemIndexProcessor for url:%s ", url));
	    	} else {
	    		processor.indexItem(strURL);
	    	}
	    	return null;
	    	//return new ParseStatus(ParseStatus.SUCCESS).getEmptyParseResult(strURL, conf);
	    } catch (MalformedURLException e) {
	    	return new ParseStatus(e).getEmptyParseResult(content.getUrl(), getConf());
	    }
	   
  }
  
  public static void main(String[] args) throws Exception {
    //LOG.setLevel(Level.FINE);
    String name = args[0];
    String url = "file:"+name;
    File file = new File(name);
    byte[] bytes = new byte[(int)file.length()];
    DataInputStream in = new DataInputStream(new FileInputStream(file));
    in.readFully(bytes);
    Configuration conf = NutchConfiguration.create();
    URLParser parser = new URLParser();
    parser.setConf(conf);
    Parse parse = parser.getParse(
            new Content(url, url, bytes, "text/html", new Metadata(), conf)).get(url);
    System.out.println("data: "+parse.getData());

    System.out.println("text: "+parse.getText());
    
  }

  public void setConf(Configuration conf) {
    this.conf = conf;
  }

  public Configuration getConf() {
    return this.conf;
  }
}
