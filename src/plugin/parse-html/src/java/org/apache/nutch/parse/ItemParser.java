package org.apache.nutch.parse;

import org.apache.nutch.protocol.Content;

public interface ItemParser {
	
	/**
	 * 解析item信息
	 * @param content
	 */
	void parse(Content content);
}
