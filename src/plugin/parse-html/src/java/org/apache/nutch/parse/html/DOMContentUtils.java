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

package org.apache.nutch.parse.html;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.nutch.matrix.bridge.MatrixBridge;
import org.apache.nutch.parse.Outlink;
import org.apache.nutch.protocol.Content;
import org.apache.nutch.util.NodeWalker;
import org.apache.nutch.util.URLUtil;
import org.cyberneko.html.parsers.DOMParser;
import org.dom4j.XPath;
import org.dom4j.io.DOMReader;
import org.dom4j.xpath.DefaultXPath;
import org.jaxen.SimpleNamespaceContext;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.ihome.matrix.domain.CategoryDO;
import com.ihome.matrix.domain.ItemDO;
import com.ihome.matrix.enums.PlatformEnum;
import com.ihome.matrix.enums.StuffStatusEnum;

/**
 * A collection of methods for extracting content from DOM trees.
 * 
 * This class holds a few utility methods for pulling content out of 
 * DOM nodes, such as getOutlinks, getText, etc.
 *
 */
public class DOMContentUtils {

	private static final Log logger = LogFactory.getLog(DOMContentUtils.class);
	
	public static class LinkParams {
    public String elName;
    public String attrName;
      public int childLen;
      
      public LinkParams(String elName, String attrName, int childLen) {
          this.elName = elName;
          this.attrName = attrName;
          this.childLen = childLen;
      }
      
      public String toString() {
          return "LP[el=" + elName + ",attr=" + attrName + ",len=" + childLen + "]";
      }
  }
  
  private HashMap<String,LinkParams> linkParams = new HashMap<String,LinkParams>();
  private Configuration conf;
  
  public DOMContentUtils(Configuration conf) {
    setConf(conf);
  }
  
  public void setConf(Configuration conf) {
    // forceTags is used to override configurable tag ignoring, later on
    Collection<String> forceTags = new ArrayList<String>(1);

    this.conf = conf;
    linkParams.clear();
    linkParams.put("a", new LinkParams("a", "href", 1));
    linkParams.put("area", new LinkParams("area", "href", 0));
    if (conf.getBoolean("parser.html.form.use_action", true)) {
      linkParams.put("form", new LinkParams("form", "action", 1));
      if (conf.get("parser.html.form.use_action") != null)
        forceTags.add("form");
    }
    linkParams.put("frame", new LinkParams("frame", "src", 0));
    linkParams.put("iframe", new LinkParams("iframe", "src", 0));
    linkParams.put("script", new LinkParams("script", "src", 0));
    linkParams.put("link", new LinkParams("link", "href", 0));
    linkParams.put("img", new LinkParams("img", "src", 0));

    // remove unwanted link tags from the linkParams map
    String[] ignoreTags = conf.getStrings("parser.html.outlinks.ignore_tags");
    for ( int i = 0 ; ignoreTags != null && i < ignoreTags.length ; i++ ) {
      if ( ! forceTags.contains(ignoreTags[i]) )
        linkParams.remove(ignoreTags[i]);
    }
  }
  
  /**
   * This method takes a {@link StringBuffer} and a DOM {@link Node},
   * and will append all the content text found beneath the DOM node to 
   * the <code>StringBuffer</code>.
   *
   * <p>
   *
   * If <code>abortOnNestedAnchors</code> is true, DOM traversal will
   * be aborted and the <code>StringBuffer</code> will not contain
   * any text encountered after a nested anchor is found.
   * 
   * <p>
   *
   * @return true if nested anchors were found
   */
  public static boolean getText(StringBuffer sb, Node node, 
                                      boolean abortOnNestedAnchors) {
    if (getTextHelper(sb, node, abortOnNestedAnchors, 0)) {
      return true;
    } 
    return false;
  }


  /**
   * This is a convinience method, equivalent to {@link
   * #getText(StringBuffer,Node,boolean) getText(sb, node, false)}.
   * 
   */
  public static void getText(StringBuffer sb, Node node) {
    getText(sb, node, false);
  }

  // returns true if abortOnNestedAnchors is true and we find nested 
  // anchors
  private static boolean getTextHelper(StringBuffer sb, Node node, 
                                             boolean abortOnNestedAnchors,
                                             int anchorDepth) {
    boolean abort = false;
    NodeWalker walker = new NodeWalker(node);
    
    while (walker.hasNext()) {
    
      Node currentNode = walker.nextNode();
      String nodeName = currentNode.getNodeName();
      short nodeType = currentNode.getNodeType();
      
      if ("script".equalsIgnoreCase(nodeName)) {
        walker.skipChildren();
      }
      if ("style".equalsIgnoreCase(nodeName)) {
        walker.skipChildren();
      }
      if (abortOnNestedAnchors && "a".equalsIgnoreCase(nodeName)) {
        anchorDepth++;
        if (anchorDepth > 1) {
          abort = true;
          break;
        }        
      }
      if (nodeType == Node.COMMENT_NODE) {
        walker.skipChildren();
      }
      if (nodeType == Node.TEXT_NODE) {
        // cleanup and trim the value
        String text = currentNode.getNodeValue();
        text = text.replaceAll("\\s+", " ");
        text = text.trim();
        if (text.length() > 0) {
          if (sb.length() > 0) sb.append(' ');
        	sb.append(text);
        }
      }
    }
    
    return abort;
  }

  /**
   * This method takes a {@link StringBuffer} and a DOM {@link Node},
   * and will append the content text found beneath the first
   * <code>title</code> node to the <code>StringBuffer</code>.
   *
   * @return true if a title node was found, false otherwise
   */
  public boolean getTitle(StringBuffer sb, Node node) {
    
    NodeWalker walker = new NodeWalker(node);
    
    while (walker.hasNext()) {
  
      Node currentNode = walker.nextNode();
      String nodeName = currentNode.getNodeName();
      short nodeType = currentNode.getNodeType();
      
      if ("body".equalsIgnoreCase(nodeName)) { // stop after HEAD
        return false;
      }
  
      if (nodeType == Node.ELEMENT_NODE) {
        if ("title".equalsIgnoreCase(nodeName)) {
          getText(sb, currentNode);
          return true;
        }
      }
    }      
    
    return false;
  }

  /** If Node contains a BASE tag then it's HREF is returned. */
  public URL getBase(Node node) {

    NodeWalker walker = new NodeWalker(node);
    
    while (walker.hasNext()) {
  
      Node currentNode = walker.nextNode();
      String nodeName = currentNode.getNodeName();
      short nodeType = currentNode.getNodeType();
      
      // is this node a BASE tag?
      if (nodeType == Node.ELEMENT_NODE) {
  
        if ("body".equalsIgnoreCase(nodeName)) { // stop after HEAD
          return null;
        }
  
        if ("base".equalsIgnoreCase(nodeName)) {
          NamedNodeMap attrs = currentNode.getAttributes();
          for (int i= 0; i < attrs.getLength(); i++ ) {
            Node attr = attrs.item(i);
            if ("href".equalsIgnoreCase(attr.getNodeName())) {
              try {
                return new URL(attr.getNodeValue());
              } catch (MalformedURLException e) {}
            }
          }
        }
      }
    }

    // no.
    return null;
  }


  private boolean hasOnlyWhiteSpace(Node node) {
    String val= node.getNodeValue();
    for (int i= 0; i < val.length(); i++) {
      if (!Character.isWhitespace(val.charAt(i)))
        return false;
    }
    return true;
  }

  // this only covers a few cases of empty links that are symptomatic
  // of nekohtml's DOM-fixup process...
  private boolean shouldThrowAwayLink(Node node, NodeList children, 
                                              int childLen, LinkParams params) {
    if (childLen == 0) {
      // this has no inner structure 
      if (params.childLen == 0) return false;
      else return true;
    } else if ((childLen == 1) 
               && (children.item(0).getNodeType() == Node.ELEMENT_NODE)
               && (params.elName.equalsIgnoreCase(children.item(0).getNodeName()))) { 
      // single nested link
      return true;

    } else if (childLen == 2) {

      Node c0= children.item(0);
      Node c1= children.item(1);

      if ((c0.getNodeType() == Node.ELEMENT_NODE)
          && (params.elName.equalsIgnoreCase(c0.getNodeName()))
          && (c1.getNodeType() == Node.TEXT_NODE) 
          && hasOnlyWhiteSpace(c1) ) {
        // single link followed by whitespace node
        return true;
      }

      if ((c1.getNodeType() == Node.ELEMENT_NODE)
          && (params.elName.equalsIgnoreCase(c1.getNodeName()))
          && (c0.getNodeType() == Node.TEXT_NODE) 
          && hasOnlyWhiteSpace(c0) ) {
        // whitespace node followed by single link
        return true;
      }

    } else if (childLen == 3) {
      Node c0= children.item(0);
      Node c1= children.item(1);
      Node c2= children.item(2);
      
      if ((c1.getNodeType() == Node.ELEMENT_NODE)
          && (params.elName.equalsIgnoreCase(c1.getNodeName()))
          && (c0.getNodeType() == Node.TEXT_NODE) 
          && (c2.getNodeType() == Node.TEXT_NODE) 
          && hasOnlyWhiteSpace(c0)
          && hasOnlyWhiteSpace(c2) ) {
        // single link surrounded by whitespace nodes
        return true;
      }
    }

    return false;
  }
  
  /**
   * This method finds all anchors below the supplied DOM
   * <code>node</code>, and creates appropriate {@link Outlink}
   * records for each (relative to the supplied <code>base</code>
   * URL), and adds them to the <code>outlinks</code> {@link
   * ArrayList}.
   *
   * <p>
   *
   * Links without inner structure (tags, text, etc) are discarded, as
   * are links which contain only single nested links and empty text
   * nodes (this is a common DOM-fixup artifact, at least with
   * nekohtml).
   */
  public void getOutlinks(URL base, ArrayList<Outlink> outlinks, 
                                       Node node) {
    
    NodeWalker walker = new NodeWalker(node);
    while (walker.hasNext()) {
      
      Node currentNode = walker.nextNode();
      String nodeName = currentNode.getNodeName();
      short nodeType = currentNode.getNodeType();      
      NodeList children = currentNode.getChildNodes();
      int childLen = (children != null) ? children.getLength() : 0; 
      
      if (nodeType == Node.ELEMENT_NODE) {
        
        nodeName = nodeName.toLowerCase();
        LinkParams params = (LinkParams)linkParams.get(nodeName);
        if (params != null) {
          if (!shouldThrowAwayLink(currentNode, children, childLen, params)) {
  
            StringBuffer linkText = new StringBuffer();
            getText(linkText, currentNode, true);
            if (linkText.toString().trim().length() == 0) {
              // try harder - use img alt if present
              NodeWalker subWalker = new NodeWalker(currentNode);
              while (subWalker.hasNext()) {
                Node subNode = subWalker.nextNode();
                if (subNode.getNodeType() == Node.ELEMENT_NODE) {
                  if (subNode.getNodeName().toLowerCase().equals("img")) {
                    NamedNodeMap subAttrs = subNode.getAttributes();
                    Node alt = subAttrs.getNamedItem("alt");
                    if (alt != null) {
                      String altTxt = alt.getTextContent();
                      if (altTxt != null && altTxt.trim().length() > 0) {
                        if (linkText.length() > 0) linkText.append(' ');
                        linkText.append(altTxt);
                      }
                    }
                  } else {
                    // ignore other types of elements
                    
                  } 
                } else if (subNode.getNodeType() == Node.TEXT_NODE) {
                  String txt = subNode.getTextContent();
                  if (txt != null && txt.length() > 0) {
                    if (linkText.length() > 0) linkText.append(' ');
                    linkText.append(txt);
                  }                  
                }
              }
            }
  
            NamedNodeMap attrs = currentNode.getAttributes();
            String target = null;
            boolean noFollow = false;
            boolean post = false;
            for (int i= 0; i < attrs.getLength(); i++ ) {
              Node attr = attrs.item(i);
              String attrName = attr.getNodeName();
              if (params.attrName.equalsIgnoreCase(attrName)) {
                target = attr.getNodeValue();
              } else if ("rel".equalsIgnoreCase(attrName) &&
                         "nofollow".equalsIgnoreCase(attr.getNodeValue())) {
                noFollow = true;
              } else if ("method".equalsIgnoreCase(attrName) &&
                         "post".equalsIgnoreCase(attr.getNodeValue())) {
                post = true;
              }
            }
            if (target != null && !noFollow && !post)
              try {
                
                URL url = URLUtil.resolveURL(base, target);
                outlinks.add(new Outlink(url.toString(),
                                         linkText.toString().trim()));
              } catch (MalformedURLException e) {
                // don't care
              }
          }
          // this should not have any children, skip them
          if (params.childLen == 0) continue;
        }
      }
    }
  }
  
  
  static String JING_DONG_ITEM_ID_XPATH = "/xmlns:html";
  
  /**
   * 
   * @param content
   * @return
   */
  public static ItemDO getJingdongItemV2(Content content) {
	  try {
		  ItemDO item = new ItemDO();
		  item.setPlatform(PlatformEnum.PLATFORM_360_BUY.getValue());
		  item.setShop(MatrixBridge.getFixedShop(PlatformEnum.PLATFORM_360_BUY));
		  item.setDetailURL(content.getUrl());
		  item.setStuffStatus(StuffStatusEnum.STUFF_NEW.getValue());
		  item.setNumber(-1L);
		  
		  byte[] contentInOctets = content.getContent();
	      InputSource input = new InputSource(new ByteArrayInputStream(contentInOctets));
	      DOMParser parser = new DOMParser();
	      parser.parse(input);
	      org.w3c.dom.Document w3cDoc=parser.getDocument(); 
	      DOMReader domReader = new DOMReader();
	      org.dom4j.Document document = domReader.read(w3cDoc);
		  
		  Map<String, String> nameSpaces = new HashMap<String, String>();  
		  XPath xpath=new DefaultXPath(JING_DONG_ITEM_ID_XPATH);  
	      nameSpaces.put("xmlns","http://www.w3.org/1999/xhtml");  
	      xpath.setNamespaceContext(new SimpleNamespaceContext(nameSpaces));
		  Object itemIdNode = xpath.selectSingleNode(document);
		  
		  return item;
	  } catch (IOException e) {
		  logger.error(e);
	  } catch (SAXException e) {
		  logger.error(e);
	  }
	  return null;
  }

  static String COO8_ITEM_ID_XPATH = "//*[@id='prod-markprice']/xmlns:DD";
  static String COO8_ITEM_CATEGORY_AND_NAME_XPATH = "/xmlns:HTML/xmlns:BODY/xmlns:DIV[4]/xmlns:DIV[1]/xmlns:A/text()";
  static String COO8_ITEM_PRICE_XPATH = "//*[@id='itemimg']/@src";
  static String COO8_ITEM_PHOTO_XPATH = "//*[@id='tmp']/xmlns:DIV/xmlns:DIV/xmlns:DIV[1]/xmlns:A/@href";
  
  /**
   * 
   * @param content
   * @return
   */
  public static ItemDO getCool8Item(Content content) {
	  try {
		  ItemDO item = new ItemDO();
		  item.setPlatform(PlatformEnum.PLATFORM_COO8.getValue());
		  item.setShop(MatrixBridge.getFixedShop(PlatformEnum.PLATFORM_COO8));
		  item.setDetailURL(content.getUrl());
		  item.setStuffStatus(StuffStatusEnum.STUFF_NEW.getValue());
		  item.setNumber(-1L);
		  
		  //System.out.println(content.toString());
		  byte[] contentInOctets = content.getContent();
	      InputSource input = new InputSource(new ByteArrayInputStream(contentInOctets));
	      DOMParser parser = new DOMParser();
	      parser.parse(input);
	      org.w3c.dom.Document w3cDoc = parser.getDocument(); 
	      DOMReader domReader = new DOMReader();
	      org.dom4j.Document document = domReader.read(w3cDoc);
		  Map<String, String> nameSpaces = new HashMap<String, String>();  
	      nameSpaces.put("xmlns","http://www.w3.org/1999/xhtml");
	      SimpleNamespaceContext context = new SimpleNamespaceContext(nameSpaces);
	      
	      // itemId
	      XPath xpath = new DefaultXPath(COO8_ITEM_ID_XPATH);
	      xpath.setNamespaceContext(context);
		  Object node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setItemId(((org.dom4j.Node)node).getText());
		  }
		  
		  // category and name
		  List<String> categoryPath = new ArrayList<String>(3);
		  xpath = new DefaultXPath(COO8_ITEM_CATEGORY_AND_NAME_XPATH);
	      xpath.setNamespaceContext(context);
		  node = xpath.selectNodes(document);
		  if(null != node) {
			  int length = ((List<org.dom4j.Node>)node).size();
			  int i = 0;
			  for(org.dom4j.Node n : (List<org.dom4j.Node>)node) {
				  if(++i == length) {
					  item.setName(n.getText());
				  } else {
					  categoryPath.add(n.getText());
				  }
			  }
		  }
		  
		  // 生成类目树
		  CategoryDO category = generateCategoryTree(categoryPath);
		  item.setCategory(category);
		  
		  // price
		  xpath = new DefaultXPath(COO8_ITEM_PRICE_XPATH);  
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(discernCoo8Price(((org.dom4j.Attribute)node).getValue()));
		  }
		  
		  // photo
		  xpath = new DefaultXPath(COO8_ITEM_PHOTO_XPATH);  
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setLogoURL(generatePhoto(((org.dom4j.Attribute)node).getValue()));
		  }
		  
		  return item;
	  } catch (IOException e) {
		  logger.error(e);
	  } catch (SAXException e) {
		  logger.error(e);
	  }
	  
	  return null;
  }
  
  static final String AMAZON_ITEM_NAME_XPATH = "//*[@id='btAsinTitle']/text()";
  
  static final String AMAZON_ITEM_PRICE_XPATH = "//*[@id='actualPriceValue']/B";
  /**
   * 
   * @param content
   * @return
   */
  public static ItemDO getAmazonItem(Content content) {
	  try {
		  ItemDO item = new ItemDO();
		  item.setPlatform(PlatformEnum.PLATFORM_AMAZON.getValue());
		  item.setShop(MatrixBridge.getFixedShop(PlatformEnum.PLATFORM_AMAZON));
		  item.setDetailURL(content.getUrl());
		  item.setStuffStatus(StuffStatusEnum.STUFF_NEW.getValue());
		  item.setNumber(-1L);
		  
		  //System.out.println(content.toString());
		  byte[] contentInOctets = content.getContent();
	      InputSource input = new InputSource(new ByteArrayInputStream(contentInOctets));
	      DOMParser parser = new DOMParser();
	      parser.parse(input);
	      org.w3c.dom.Document w3cDoc = parser.getDocument(); 
	      DOMReader domReader = new DOMReader();
	      org.dom4j.Document document = domReader.read(w3cDoc);
	      
	      // itemId
	      XPath xpath = new DefaultXPath(COO8_ITEM_ID_XPATH);
		  Object node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setItemId(((org.dom4j.Node)node).getText());
		  }
		  
		  // itemName
		  xpath = new DefaultXPath(AMAZON_ITEM_NAME_XPATH);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setName(((org.dom4j.Node)node).getText());
		  }
		  
		  // itemPrice
		  xpath = new DefaultXPath(AMAZON_ITEM_PRICE_XPATH);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(Double.valueOf(((org.dom4j.Node)node).getText().substring("￥ ".length()).replaceAll(",", "")));
		  }
		  
		  // photo
		  String html = content.toString();
		  int start = html.indexOf("\"hiResImage\":");
		  if(-1 != start) {
			  int end = html.indexOf(",", start);
			  if(-1 != end) {
				  item.setLogoURL(html.substring(start + "\"hiResImage\":".length() + "\"".length(), end - "\"".length()));
			  }
		  }
		  return item;
	  } catch (IOException e) {
		  logger.error(e);
	  } catch (SAXException e) {
		  logger.error(e);
	  }
	  
	  return null;
  }
  
  static final String DANGDANG_ITEM_ID_XPATH = "";
  static final String DANGDANG_PRODUCT_PARAMETER_ID = "product_id";
  static final String DNAGDANG_ITEM_NAME_XPATH = "/xmlns:HTML/xmlns:BODY/xmlns:DIV[3]/xmlns:DIV[4]/xmlns:DIV[1]/xmlns:H1";
  static final String DANGDANG_ITEM_PRICE_XPATH = "//*[@id='salePriceTag']";
  static final String DANGDANG_ITEM_PHOTO_XPATH = "//*[@id='largePic']/@src";
  static final String DANGDANG_ITEM_CATEGORY_XPATH = "/xmlns:HTML/xmlns:BODY/xmlns:DIV[3]/xmlns:DIV[3]/xmlns:A/text()";
  
  /**
   * 
   * @param content
   * @return
   */
  public static ItemDO getDangdangItem(Content content) {
	  try {
		  ItemDO item = new ItemDO();
		  item.setPlatform(PlatformEnum.PLATFORM_DANGDANG.getValue());
		  item.setShop(MatrixBridge.getFixedShop(PlatformEnum.PLATFORM_DANGDANG));
		  item.setDetailURL(content.getUrl());
		  item.setStuffStatus(StuffStatusEnum.STUFF_NEW.getValue());
		  item.setNumber(-1L);
		  
		  //System.out.println(content.toString());
		  byte[] contentInOctets = content.getContent();
	      InputSource input = new InputSource(new ByteArrayInputStream(contentInOctets));
	      DOMParser parser = new DOMParser();
	      parser.parse(input);
	      org.w3c.dom.Document w3cDoc = parser.getDocument(); 
	      DOMReader domReader = new DOMReader();
	      org.dom4j.Document document = domReader.read(w3cDoc);
	      Map<String, String> nameSpaces = new HashMap<String, String>();  
	      nameSpaces.put("xmlns","http://www.w3.org/1999/xhtml");
	      SimpleNamespaceContext context = new SimpleNamespaceContext(nameSpaces);
	      
	      // itemId
	      item.setItemId(URLUtil.getParameter(content.getUrl(), DANGDANG_PRODUCT_PARAMETER_ID));
	      
	      // itemName
		  XPath xpath = new DefaultXPath(DNAGDANG_ITEM_NAME_XPATH);
		  xpath.setNamespaceContext(context);
		  Object node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setName(((org.dom4j.Node)node).getText());
		  }
		  
		  // item category
		  List<String> categoryPath = new ArrayList<String>(3);
		  xpath = new DefaultXPath(DANGDANG_ITEM_CATEGORY_XPATH);
	      xpath.setNamespaceContext(context);
		  node = xpath.selectNodes(document);
		  if(null != node) {
			  int length = ((List<org.dom4j.Node>)node).size();
			  int i = 0;
			  for(org.dom4j.Node n : (List<org.dom4j.Node>)node) {
				  if(++i == 1) {
					  continue;
				  } else {
					  categoryPath.add(n.getText());
				  }
			  }
		  }
		  
		  // 生成类目树
		  CategoryDO category = generateCategoryTree(categoryPath);
		  item.setCategory(category);
		  
		  
		  // itemPrice
		  xpath = new DefaultXPath(DANGDANG_ITEM_PRICE_XPATH);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(Double.valueOf(((org.dom4j.Node)node).getText().substring("￥".length()).replaceAll(",", "")));
		  }
		  
		  // TODO 优惠价格
		  
		  // photo
		  xpath = new DefaultXPath(DANGDANG_ITEM_PHOTO_XPATH);  
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setLogoURL(generatePhoto(((org.dom4j.Attribute)node).getValue()));
		  }
		  
		  return item;
	  } catch (IOException e) {
		  logger.error(e);
	  } catch (SAXException e) {
		  logger.error(e);
	  }
	  
	  return null;
  }
  
  static final String GOME_ITEM_ID_XPATH = "//*[@id='prodNum']/text()";
  static final String GOME_ITEM_NAME_XPATH = "//*[@id='prodDisplayNaLoad']/DIV/H2";
  static final String GOME_ITEM_PRICE_XPATH = "/HTML/BODY/DIV[6]/DIV[1]/DIV[3]/DIV[3]/B";
  static final String GOME_ITEM_PHOTO_XPATH = "//*[@id='pic_1']/@bgpic";
  static final String GOME_ITEM_CATEGORY_XPATH = "/HTML/BODY/DIV[4]/A/text()";
  
  /**
   * 
   * @param content
   * @return
   */
  public static ItemDO getGomeItem(Content content) {
	  try {
		  ItemDO item = new ItemDO();
		  item.setPlatform(PlatformEnum.PLATFORM_GOME.getValue());
		  item.setShop(MatrixBridge.getFixedShop(PlatformEnum.PLATFORM_GOME));
		  item.setDetailURL(content.getUrl());
		  item.setStuffStatus(StuffStatusEnum.STUFF_NEW.getValue());
		  item.setNumber(-1L);
		  
		  System.out.println(content.toString());
		  byte[] contentInOctets = content.getContent();
	      InputSource input = new InputSource(new ByteArrayInputStream(contentInOctets));
	      DOMParser parser = new DOMParser();
	      parser.parse(input);
	      org.w3c.dom.Document w3cDoc = parser.getDocument(); 
	      DOMReader domReader = new DOMReader();
	      org.dom4j.Document document = domReader.read(w3cDoc);
	      
	      // itemId
	      XPath xpath = new DefaultXPath(GOME_ITEM_ID_XPATH);
		  Object node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setName(((org.dom4j.Node)node).getText());
		  }
		  
	      // itemName
		  xpath = new DefaultXPath(GOME_ITEM_NAME_XPATH);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setName(((org.dom4j.Node)node).getText());
		  }
		  
		  // item category
		  List<String> categoryPath = new ArrayList<String>(3);
		  xpath = new DefaultXPath(GOME_ITEM_CATEGORY_XPATH);
		  node = xpath.selectNodes(document);
		  if(null != node) {
			  int length = ((List<org.dom4j.Node>)node).size();
			  int i = 0;
			  for(org.dom4j.Node n : (List<org.dom4j.Node>)node) {
				  if(++i == 1) {
					  continue;
				  } else {
					  categoryPath.add(n.getText());
				  }
			  }
		  }
		  
		  // 生成类目树
		  CategoryDO category = generateCategoryTree(categoryPath);
		  item.setCategory(category);
		  
		  
		  // itemPrice
		  xpath = new DefaultXPath(GOME_ITEM_PRICE_XPATH);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(Double.valueOf(((org.dom4j.Node)node).getText().replaceAll(",", "").trim()));
		  }
		  
		  // TODO 优惠价格
		  
		  // photo
		  xpath = new DefaultXPath(GOME_ITEM_PHOTO_XPATH);  
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setLogoURL(generatePhoto(((org.dom4j.Attribute)node).getValue()));
		  }
		  
		  return item;
	  } catch (IOException e) {
		  logger.error(e);
	  } catch (SAXException e) {
		  logger.error(e);
	  }
	  
	  return null;
  }
  
  static final String SUNING_ITEM_NAME_XPATH = "/xmlns:HTML/xmlns:BODY/xmlns:DIV[5]/xmlns:SPAN";
  static final String SUNING_ITEM_CATEGORY_XPATH = "/xmlns:HTML/xmlns:BODY/xmlns:DIV[5]/xmlns:A";
  static final String SUNING_ITEM_PRICE_XPATH = "//*[@id='mainPrice']/xmlns:EM";
  static final String SUNING_ITEM_PHOTO_XPATH = "//*[@id='preView_box']/xmlns:DIV/xmlns:UL/xmlns:LI[1]/xmlns:IMG/@src2";
  
  /**
   * 
   * @param content
   * @return
   */
  public static ItemDO getSuningItem(Content content) {
	  try {
		  ItemDO item = new ItemDO();
		  item.setPlatform(PlatformEnum.PLATFORM_SUNING.getValue());
		  item.setShop(MatrixBridge.getFixedShop(PlatformEnum.PLATFORM_SUNING));
		  item.setDetailURL(content.getUrl());
		  item.setStuffStatus(StuffStatusEnum.STUFF_NEW.getValue());
		  item.setNumber(-1L);
		  
		  System.out.println(content.toString());
		  byte[] contentInOctets = content.getContent();
	      InputSource input = new InputSource(new ByteArrayInputStream(contentInOctets));
	      DOMParser parser = new DOMParser();
	      parser.parse(input);
	      org.w3c.dom.Document w3cDoc = parser.getDocument(); 
	      DOMReader domReader = new DOMReader();
	      org.dom4j.Document document = domReader.read(w3cDoc);
	      
	      Map<String, String> nameSpaces = new HashMap<String, String>();  
	      nameSpaces.put("xmlns","http://www.w3.org/1999/xhtml");
	      SimpleNamespaceContext context = new SimpleNamespaceContext(nameSpaces);
	      
	      // itemId
	      /*XPath xpath = new DefaultXPath(SUNING_ITEM_ID_XPATH);
	      xpath.setNamespaceContext(context);
		  Object node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setName(((org.dom4j.Node)node).getText());
		  }*/
	      
	      // itemName
	      XPath xpath = new DefaultXPath(SUNING_ITEM_NAME_XPATH);
	      xpath.setNamespaceContext(context);
		  Object node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setName(((org.dom4j.Node)node).getText());
		  }
		  
	      // category
		  List<String> categoryPath = new ArrayList<String>(3);
		  xpath = new DefaultXPath(SUNING_ITEM_CATEGORY_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectNodes(document);
		  if(null != node) {
			  int length = ((List<org.dom4j.Node>)node).size();
			  int i = 0;
			  for(org.dom4j.Node n : (List<org.dom4j.Node>)node) {
				  if(++i != 1) {
					  categoryPath.add(n.getText());
				  }
			  }
		  }
		  
		  // 生成类目树
		  CategoryDO category = generateCategoryTree(categoryPath);
		  item.setCategory(category);
		  
		  // itemPrice
		 /* xpath = new DefaultXPath(SUNING_ITEM_PRICE_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(Double.valueOf(((org.dom4j.Node)node).getText().replaceAll(",", "").trim()));
		  }*/
		  
		  // TODO 优惠价格
		  
		  // photo
		  xpath = new DefaultXPath(SUNING_ITEM_PHOTO_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setLogoURL(generatePhoto(((org.dom4j.Attribute)node).getValue()));
		  }
		  
		  return item;
	  } catch (IOException e) {
		  logger.error(e);
	  } catch (SAXException e) {
		  logger.error(e);
	  }
	  
	  return null;
  }
  
  static final String NEW_EGG_ITEM_NAME_XPATH = "//*[@id=\'pro215419\']";
  static final String NEW_EGG_ITEM_CATEGORY_XPATH = "//*[@id=\'crumb\']/xmlns:DIV/xmlns:A";
  static final String NEW_EGG_ITEM_PHOTO_XPATH = "//*[@id='thumbnails1']/xmlns:DIV/xmlns:UL/xmlns:LI[1]/xmlns:A/xmlns:IMG/@ref2";
  static final String NEW_EGG_ITEM_PRICE_XPATH = "//*[@id='proMainInfo']/xmlns:DIV[2]/xmlns:DIV[1]/xmlns:DL/xmlns:DD[4]/xmlns:DEL";
  static final String NEW_EGG_ITEM_PROMOTION_PRICE_XPATH = "//*[@id='proMainInfo']/xmlns:DIV[2]/xmlns:DIV[1]/xmlns:DL/xmlns:DD[5]/xmlns:p[1]/xmlns:IMG/@src";
  
  /**
   * 
   * @param content
   * @return
   */
  public static ItemDO getNewEggItem(Content content) {
	  try {
		  ItemDO item = new ItemDO();
		  item.setPlatform(PlatformEnum.PLATFORM_NEW_EGG.getValue());
		  item.setShop(MatrixBridge.getFixedShop(PlatformEnum.PLATFORM_NEW_EGG));
		  item.setDetailURL(content.getUrl());
		  item.setStuffStatus(StuffStatusEnum.STUFF_NEW.getValue());
		  item.setNumber(-1L);
		  
		  System.out.println(content.toString());
		  byte[] contentInOctets = content.getContent();
	      InputSource input = new InputSource(new ByteArrayInputStream(contentInOctets));
	      DOMParser parser = new DOMParser();
	      parser.parse(input);
	      org.w3c.dom.Document w3cDoc = parser.getDocument(); 
	      DOMReader domReader = new DOMReader();
	      org.dom4j.Document document = domReader.read(w3cDoc);
	      
	      Map<String, String> nameSpaces = new HashMap<String, String>();  
	      nameSpaces.put("xmlns","http://www.w3.org/1999/xhtml");
	      SimpleNamespaceContext context = new SimpleNamespaceContext(nameSpaces);
	      
	      // itemId
	      /*XPath xpath = new DefaultXPath(SUNING_ITEM_ID_XPATH);
	      xpath.setNamespaceContext(context);
		  Object node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setName(((org.dom4j.Node)node).getText());
		  }*/
	      
	      // itemName
	      XPath xpath = new DefaultXPath(NEW_EGG_ITEM_NAME_XPATH);
	      xpath.setNamespaceContext(context);
		  Object node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setName(((org.dom4j.Node)node).getText());
		  }
		  
	      // category
		  List<String> categoryPath = new ArrayList<String>(3);
		  xpath = new DefaultXPath(NEW_EGG_ITEM_CATEGORY_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectNodes(document);
		  if(null != node) {
			  int length = ((List<org.dom4j.Node>)node).size();
			  int i = 0;
			  for(org.dom4j.Node n : (List<org.dom4j.Node>)node) {
				  if(++i != 1 && i != length) {
					  categoryPath.add(n.getText());
				  }
			  }
		  }
		  
		  // 生成类目树
		  CategoryDO category = generateCategoryTree(categoryPath);
		  item.setCategory(category);
		  
		  // itemPrice
		  xpath = new DefaultXPath(NEW_EGG_ITEM_PRICE_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(DOMContentUtils.discernNewEggPrice((((org.dom4j.Node)node).getText())));
		  }
		  
		  // TODO 优惠价格
		  
		  // photo
		  xpath = new DefaultXPath(NEW_EGG_ITEM_PHOTO_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setLogoURL(generatePhoto(((org.dom4j.Attribute)node).getValue()));
		  }
		  
		  return item;
	  } catch (IOException e) {
		  logger.error(e);
	  } catch (SAXException e) {
		  logger.error(e);
	  }
	  
	  return null;
  }
  
  static final String NO1_SHOP_ITEM_NAME_XPATH = "/xmlns:HTML/xmlns:BODY/xmlns:DIV[5]/xmlns:DIV[3]/xmlns:DIV[1]/xmlns:H2/xmlns:FONT";
  static final String NO1_SHOP_ITEM_CATEGORY_XPATH = "/xmlns:HTML/xmlns:BODY/xmlns:DIV[5]/xmlns:DIV[2]/xmlns:SPAN/xmlns:A";
  static final String NO1_SHOP_ITEM_PHOTO_XPATH = "//*[@id='productImg']/@src";
  static final String NO1_SHOP_ITEM_PRICE_XPATH = "//*[@id='nonMemberPrice']/xmlns:STRONG";
  static final String NO1_SHOP_ITEM_PROMOTION_PRICE_XPATH = "//*[@id='proMainInfo']/xmlns:DIV[2]/xmlns:DIV[1]/xmlns:DL/xmlns:DD[5]/xmlns:p[1]/xmlns:IMG/@src";
  
  /**
   * 
   * @param content
   * @return
   */
  public static ItemDO getNo1ShopItem(Content content) {
	  try {
		  ItemDO item = new ItemDO();
		  item.setPlatform(PlatformEnum.PLATFORM_NO_1_SHOP.getValue());
		  item.setShop(MatrixBridge.getFixedShop(PlatformEnum.PLATFORM_NO_1_SHOP));
		  item.setDetailURL(content.getUrl());
		  item.setStuffStatus(StuffStatusEnum.STUFF_NEW.getValue());
		  item.setNumber(-1L);
		  
		  //System.out.println(content.toString());
		  byte[] contentInOctets = content.getContent();
	      InputSource input = new InputSource(new ByteArrayInputStream(contentInOctets));
	      DOMParser parser = new DOMParser();
	      parser.parse(input);
	      org.w3c.dom.Document w3cDoc = parser.getDocument(); 
	      DOMReader domReader = new DOMReader();
	      org.dom4j.Document document = domReader.read(w3cDoc);
	      
	      Map<String, String> nameSpaces = new HashMap<String, String>();  
	      nameSpaces.put("xmlns","http://www.w3.org/1999/xhtml");
	      SimpleNamespaceContext context = new SimpleNamespaceContext(nameSpaces);
	      
	      // itemId
	      /*XPath xpath = new DefaultXPath(SUNING_ITEM_ID_XPATH);
	      xpath.setNamespaceContext(context);
		  Object node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setName(((org.dom4j.Node)node).getText());
		  }*/
	      
	      // itemName
	      XPath xpath = new DefaultXPath(NO1_SHOP_ITEM_NAME_XPATH);
	      xpath.setNamespaceContext(context);
		  Object node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setName(((org.dom4j.Node)node).getText());
		  }
		  
	      // category
		  List<String> categoryPath = new ArrayList<String>(3);
		  xpath = new DefaultXPath(NO1_SHOP_ITEM_CATEGORY_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectNodes(document);
		  if(null != node) {
			  int length = ((List<org.dom4j.Node>)node).size();
			  int i = 0;
			  for(org.dom4j.Node n : (List<org.dom4j.Node>)node) {
				  if(++i != 1 && i != length) {
					  categoryPath.add(n.getText());
				  }
			  }
		  }
		  
		  // 生成类目树
		  CategoryDO category = generateCategoryTree(categoryPath);
		  item.setCategory(category);
		  
		  // itemPrice
		  xpath = new DefaultXPath(NO1_SHOP_ITEM_PRICE_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(Double.valueOf((((org.dom4j.Node)node).getText())));
		  }
		  
		  // TODO 优惠价格
		  
		  // photo
		  xpath = new DefaultXPath(NO1_SHOP_ITEM_PHOTO_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setLogoURL(generatePhoto(((org.dom4j.Attribute)node).getValue()));
		  }
		  
		  return item;
	  } catch (IOException e) {
		  logger.error(e);
	  } catch (SAXException e) {
		  logger.error(e);
	  }
	  
	  return null;
  }
  
  static final String FIVE_1_BUY_ITEM_NAME_XPATH = "//*[@id='container']/DIV[2]/DIV[2]/H1";
  static final String FIVE_1_BUY_ITEM_CATEGORY_XPATH = "//*[@id='container']/DIV[1]/A";
  static final String FIVE_1_BUY_ITEM_PHOTO_XPATH = "//*[@id='smallImage']/@src";
  static final String FIVE_1_BUY_ITEM_PRICE_XPATH = "//*[@id='goods_detail_mate']/LI[2]/STRONG";
  static final String FIVE_1_BUY_ITEM_PROMOTION_PRICE_XPATH = "//*[@id='proMainInfo']/xmlns:DIV[2]/xmlns:DIV[1]/xmlns:DL/xmlns:DD[5]/xmlns:p[1]/xmlns:IMG/@src";
  static final String FIVE_1_BUY_ITEM_GIFT_XPATH = "";
  
  /**
   * 
   * @param content
   * @return
   */
  public static ItemDO getFive1BuyItem(Content content) {
	  try {
		  ItemDO item = new ItemDO();
		  item.setPlatform(PlatformEnum.PLATFORM_51_BUY.getValue());
		  item.setShop(MatrixBridge.getFixedShop(PlatformEnum.PLATFORM_51_BUY));
		  item.setDetailURL(content.getUrl());
		  item.setStuffStatus(StuffStatusEnum.STUFF_NEW.getValue());
		  item.setNumber(-1L);
		  
		  //System.out.println(content.toString());
		  byte[] contentInOctets = content.getContent();
	      InputSource input = new InputSource(new ByteArrayInputStream(contentInOctets));
	      DOMParser parser = new DOMParser();
	      parser.parse(input);
	      org.w3c.dom.Document w3cDoc = parser.getDocument(); 
	      DOMReader domReader = new DOMReader();
	      org.dom4j.Document document = domReader.read(w3cDoc);
	      
	      // itemId
	      /*XPath xpath = new DefaultXPath(SUNING_ITEM_ID_XPATH);
	      xpath.setNamespaceContext(context);
		  Object node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setName(((org.dom4j.Node)node).getText());
		  }*/
	      
	      // itemName
	      XPath xpath = new DefaultXPath(FIVE_1_BUY_ITEM_NAME_XPATH);
		  Object node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setName(((org.dom4j.Node)node).getText());
		  }
		  
	      // category
		  List<String> categoryPath = new ArrayList<String>(3);
		  xpath = new DefaultXPath(FIVE_1_BUY_ITEM_CATEGORY_XPATH);
		  node = xpath.selectNodes(document);
		  if(null != node) {
			  int length = ((List<org.dom4j.Node>)node).size();
			  int i = 0;
			  for(org.dom4j.Node n : (List<org.dom4j.Node>)node) {
				  if(++i != 1) {
					  categoryPath.add(n.getText());
				  }
			  }
		  }
		  
		  // 生成类目树
		  CategoryDO category = generateCategoryTree(categoryPath);
		  item.setCategory(category);
		  
		  // itemPrice
		  xpath = new DefaultXPath(FIVE_1_BUY_ITEM_PRICE_XPATH);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(Double.valueOf((((org.dom4j.Node)node).getText())));
		  }
		  
		  // TODO 优惠价格
		  
		  // TODO photo
		  xpath = new DefaultXPath(FIVE_1_BUY_ITEM_PHOTO_XPATH);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setLogoURL(generatePhoto(((org.dom4j.Attribute)node).getValue()));
		  }
		  
		  // TODO 赠品
		 
		  return item;
	  } catch (IOException e) {
		  logger.error(e);
	  } catch (SAXException e) {
		  logger.error(e);
	  }
	  
	  return null;
  }
  
  private static CategoryDO generateCategoryTree(List<String> categoryPath) {
	  CategoryDO parent = null;
	  CategoryDO cat = null;
	  for(String catName : categoryPath) {
		  cat = new CategoryDO();
		  cat.setName(catName);
		  cat.setParent(parent);
		  parent = cat;
	  }
	  return cat;
  }
  
  public static ItemDO getJingdongItem(Node node) {
	  ItemDO item = new ItemDO();
	  item.setPlatform(PlatformEnum.PLATFORM_360_BUY.getValue());
	  item.setShop(MatrixBridge.getFixedShop(PlatformEnum.PLATFORM_360_BUY));
	  List<CategoryDO> categoryPath = new ArrayList<CategoryDO>(4);
	  NodeWalker walker = new NodeWalker(node);
	  Node currentNode = null;
	  String nodeName = null;
	  NamedNodeMap map = null;
	  Node attributeNode = null;
	  short nodeType;
	  while (walker.hasNext()) {
	      currentNode = walker.nextNode();
	      nodeName = currentNode.getNodeName();
	      nodeType = currentNode.getNodeType();
	      if (nodeType == Node.ELEMENT_NODE) {
	          if ("div".equalsIgnoreCase(nodeName)) {
	        	  map = currentNode.getAttributes();
	        	  attributeNode = map.getNamedItem("class");
	        	  if(null != attributeNode && "breadcrumb".equals(attributeNode.getNodeValue())) {
	        		  // get it
	        		  NodeWalker subWalker = new NodeWalker(currentNode);
	        		  while(subWalker.hasNext()) {
	        			  currentNode = subWalker.nextNode();
	        			  nodeName = currentNode.getNodeName();
	        		      nodeType = currentNode.getNodeType();
	        		      if (nodeType == Node.ELEMENT_NODE) {
		        		      if("a".equalsIgnoreCase(nodeName)) {
		        		    	  StringBuffer sb = new StringBuffer();
		        		    	  getText(sb, currentNode);
		        		    	  CategoryDO cat = new CategoryDO();
		        		    	  cat.setName(sb.toString());
		        		    	  categoryPath.add(cat);
		        		      }
	        		      }
	        		  }
	        	  }
	          } else if("ul".equalsIgnoreCase(nodeName)) {
	        	  map = currentNode.getAttributes();
	        	  attributeNode = map.getNamedItem("id");
	        	  if(null != attributeNode && "summary".equals(attributeNode.getNodeValue())) {
	        		  NodeWalker subWalker = new NodeWalker(currentNode);
	        		  while(subWalker.hasNext()) {
	        			  currentNode = subWalker.nextNode();
	        			  nodeName = currentNode.getNodeName();
	        		      nodeType = currentNode.getNodeType();
	        		      if (nodeType == Node.ELEMENT_NODE) {
		        		      if("li".equalsIgnoreCase(nodeName)) {
		        		    	  map = currentNode.getAttributes();
		        	        	  attributeNode = map.getNamedItem("id");
		        	        	  if(null != attributeNode && "summary-market".equals(attributeNode.getNodeValue())) {
		        	        		  NodeWalker subSubWalker = new NodeWalker(currentNode);
		        	        		  while(subSubWalker.hasNext()) {
		        	        			  currentNode = subSubWalker.nextNode();
		        	        			  nodeName = currentNode.getNodeName();
		        	        		      nodeType = currentNode.getNodeType();
		        	        		      if (nodeType == Node.ELEMENT_NODE) {
		        	        		    	  if("span".equalsIgnoreCase(nodeName)) {
		        	        		    		  StringBuffer sb = new StringBuffer();
		        		        		    	  getText(sb, currentNode);
		        	        		    		  item.setItemId(sb.toString());
		        	        		    	  }
		        	        		      }
		        	        		  }
		        	        	  } else if (null != attributeNode && "summary-price".equals(attributeNode.getNodeValue())) {
		        	        		  NodeWalker subSubWalker = new NodeWalker(currentNode);
		        	        		  while(subSubWalker.hasNext()) {
		        	        			  currentNode = subSubWalker.nextNode();
		        	        			  nodeName = currentNode.getNodeName();	
		        	        		      nodeType = currentNode.getNodeType();
		        	        		      if (nodeType == Node.ELEMENT_NODE) {
		        	        		    	  if("img".equalsIgnoreCase(nodeName)) {
		        	        		    		  // 
		        	        		    		  item.setPrice(discernJingdongPrice(currentNode.getAttributes().getNamedItem("src").getNodeValue()));
		        	        		    	  }
		        	        		      }
		        	        		  }
		        	        	  } else if (null != attributeNode && "summary-promotion".equals(attributeNode.getNodeValue())) {
		        	        		  // 优惠
		        	        		  NodeWalker subSubWalker = new NodeWalker(currentNode);
		        	        		  while(subSubWalker.hasNext()) {
		        	        			  currentNode = subSubWalker.nextNode();
		        	        			  nodeName = currentNode.getNodeName();	
		        	        		      nodeType = currentNode.getNodeType();
		        	        		      if (nodeType == Node.ELEMENT_NODE) {
		        	        		    	  if("em".equalsIgnoreCase(nodeName)) {
		        	        		    		  // 
		        	        		    		  map = currentNode.getAttributes();
		        		        	        	  attributeNode = map.getNamedItem("class");
		        		        	        	  if(null != attributeNode && "hl_red".equals(attributeNode.getNodeValue())) {
		        		        	        		  StringBuffer sb = new StringBuffer();
			        		        		    	  getText(sb, currentNode);
		        		        	        		  item.addPromotion(sb.toString());
		        		        	        	  }
		        	        		    	  }
		        	        		      }
		        	        		  }
		        	        	  } else if (null != attributeNode && "summary-gifts".equals(attributeNode.getNodeValue())) {
		        	        		  // 赠品
		        	        		  NodeWalker subSubWalker = new NodeWalker(currentNode);
		        	        		  while(subSubWalker.hasNext()) {
		        	        			  currentNode = subSubWalker.nextNode();
		        	        			  nodeName = currentNode.getNodeName();	
		        	        		      nodeType = currentNode.getNodeType();
		        	        		      if (nodeType == Node.ELEMENT_NODE) {
		        	        		    	  if("a".equalsIgnoreCase(nodeName)) {
		        	        		    		  StringBuffer sb = new StringBuffer();
		        		        		    	  getText(sb, currentNode);
		        		        		    	  Node next = subSubWalker.nextNode();
		        		        		    	  sb.append("  ");
		        		        		    	  getText(sb, next);
		        		        		    	  item.addGift(sb.toString());
		        	        		    	  }
		        	        		      }
		        	        		  }
		        	        	  } else if (null != attributeNode && "summary-grade".equals(attributeNode.getNodeValue())) {
		        	        		  // 评分
		        	        		  NodeWalker subSubWalker = new NodeWalker(currentNode);
		        	        		  while(subSubWalker.hasNext()) {
		        	        			  currentNode = subSubWalker.nextNode();
		        	        			  nodeName = currentNode.getNodeName();	
		        	        		      nodeType = currentNode.getNodeType();
		        	        		      if (nodeType == Node.ELEMENT_NODE) {
		        	        		    	  if("span".equalsIgnoreCase(nodeName)) {
		        	        		    		  // TODO
		        	        		    	  }
		        	        		      }
		        	        		  }
		        	        	  }
		        		      }
	        		      }
	        		  }
	        	  }
	          }
	     }
	  }
	  CategoryDO dumpy = categoryPath.remove(categoryPath.size() - 1);
	  item.setName(dumpy.getName());
	  return item;
  }
  
  public static String getJingdongCategory(Node node) {
	  return null;
  }
  
  public static Double discernJingdongPrice(String photoURL) {
	  System.out.println(String.format("Price photo url:%s", photoURL));
	  // FIXME
	  return 0.99D;
  }

  public static Double discernCoo8Price(String photoURL) {
	  System.out.println(String.format("Price photo url:%s", photoURL));
	  // FIXME
	  return 0.99D;
  }
  
  public static Double discernNewEggPrice(String photoURL) {
	  System.out.println(String.format("Price photo url:%s", photoURL));
	  // FIXME
	  return 0.99D;
  }
  
  /**
   * 
   * @param src
   * @return
   */
  public static String generatePhoto(String src) {
	  // Do nothing
	  return src;
  }
}

