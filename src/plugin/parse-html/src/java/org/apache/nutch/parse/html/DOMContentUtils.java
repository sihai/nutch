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

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.nutch.crawl.CrawlDatum;
import org.apache.nutch.matrix.bridge.MatrixBridge;
import org.apache.nutch.parse.Outlink;
import org.apache.nutch.protocol.Content;
import org.apache.nutch.protocol.Protocol;
import org.apache.nutch.protocol.ProtocolFactory;
import org.apache.nutch.protocol.ProtocolNotFound;
import org.apache.nutch.protocol.ProtocolOutput;
import org.apache.nutch.util.NodeWalker;
import org.apache.nutch.util.NutchConfiguration;
import org.apache.nutch.util.StringUtil;
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
import com.ihome.matrix.enums.FreightFeePayerEnum;
import com.ihome.matrix.enums.ItemStatusEnum;
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
  private static Configuration conf = NutchConfiguration.create();
  private static ProtocolFactory protocolFactory = new ProtocolFactory(conf);
  
  // OCR
  private static Tesseract instance;
  
  static {
	  instance = Tesseract.getInstance();  // JNA Interface Mapping
	  //instance.setLanguage("chi_sim");
	  //instance.setHocr(true);
	  instance.setPageSegMode(7);
  }
  
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
  
  
  static String JING_DONG_ITEM_ID_XPATH = "//*[@id='summary-market']/xmlns:DIV[2]/xmlns:SPAN/text()";
  //html/body/div[4]/div/span/a[1]
  //html/body/div[4]/div/span/a[1]
  //html/body/div[3]/div/span/a[1]
  static String JING_DONG_ITEM_CATEGORY_AND_NAME_XPATH = "/xmlns:HTML/xmlns:BODY/xmlns:DIV[3]/xmlns:DIV/xmlns:SPAN/xmlns:A";
  static String JING_DONG_ITEM_PRICE_XPATH = "//*[@id='summary-price']/xmlns:DIV[2]/xmlns:STRONG/xmlns:IMG/@src";
  static String JING_DONG_ITEM_PHOTO_XPATH = "//*[@id='spec-n1']/xmlns:IMG/@src";
  
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
		  item.setStatus(ItemStatusEnum.ITEM_STATUS_ON_SALE.getValue());
		  item.setFreightFeePayer(FreightFeePayerEnum.FREIGHT_FEE_PALYER_SELLER.getValue());
		  item.setIsDeleted(false);
		  
		  //System.out.println(content.toString());
		  byte[] contentInOctets = content.getContent();
	      InputSource input = new InputSource(new ByteArrayInputStream(contentInOctets));
	      DOMParser parser = new DOMParser();
	      parser.parse(input);
	      org.w3c.dom.Document w3cDoc=parser.getDocument(); 
	      DOMReader domReader = new DOMReader();
	      org.dom4j.Document document = domReader.read(w3cDoc);
	      Map<String, String> nameSpaces = new HashMap<String, String>();  
	      nameSpaces.put("xmlns","http://www.w3.org/1999/xhtml");
	      SimpleNamespaceContext context = new SimpleNamespaceContext(nameSpaces);
		 
		  XPath xpath=new DefaultXPath(JING_DONG_ITEM_ID_XPATH);  
	      xpath.setNamespaceContext(context);
		  Object node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setItemId(((org.dom4j.Node)node).getText());
		  }
		  
		  // category and name
		  List<String> categoryPath = new ArrayList<String>(3);
		  xpath = new DefaultXPath(JING_DONG_ITEM_CATEGORY_AND_NAME_XPATH);
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
		  xpath = new DefaultXPath(JING_DONG_ITEM_PRICE_XPATH);  
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(discernJingdongPrice(((org.dom4j.Attribute)node).getValue()));
		  }
		  
		  // photo
		  xpath = new DefaultXPath(JING_DONG_ITEM_PHOTO_XPATH);  
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
		  item.setStatus(ItemStatusEnum.ITEM_STATUS_ON_SALE.getValue());
		  item.setFreightFeePayer(FreightFeePayerEnum.FREIGHT_FEE_PALYER_SELLER.getValue());
		  item.setIsDeleted(false);
		  
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
  static final String AMAZON_ITEM_PHOTO_XPATH = "//*[@id='prodImage']/@src";
  static final String AMAZON_ITEM_PHOTO_XPATH_1 = "//*[@id='original-main-image']/@src";

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
		  item.setStatus(ItemStatusEnum.ITEM_STATUS_ON_SALE.getValue());
		  item.setFreightFeePayer(FreightFeePayerEnum.FREIGHT_FEE_PALYER_SELLER.getValue());
		  item.setIsDeleted(false);
		  
		  //System.out.println(content.toString());
		 /* Writer writer = null;
		  try {
			  writer = new BufferedWriter(new FileWriter("/home/sihai/test.html"));
			  writer.write(content.toString());
			  writer.flush();
		  } catch (IOException e) {
			  e.printStackTrace();
		  } finally{
			  if(null != writer) {
				  try {
					  writer.close();
				  } catch (IOException e) {
					  e.printStackTrace();
				  }
			  }
		  }*/
		  
		  byte[] contentInOctets = content.getContent();
	      InputSource input = new InputSource(new ByteArrayInputStream(contentInOctets));
	      DOMParser parser = new DOMParser();
	      parser.parse(input);
	      org.w3c.dom.Document w3cDoc = parser.getDocument(); 
	      DOMReader domReader = new DOMReader();
	      org.dom4j.Document document = domReader.read(w3cDoc);
	      
	      XPath xpath = null;
	      Object node = null;
	      
	      // itemId
	      String url = content.getUrl();
	      int index0 = url.indexOf("/gp/product/");
	      int index1 = -1;
	      if(-1 != index0) {
	    	  index1 = url.indexOf("/", index0 + "/gp/product/".length());
	    	  if(-1 != index1) {
	    		  item.setItemId(url.substring(index0 + "/gp/product/".length(), index1));
	    	  }
	      } else {
	    	  index0 = url.indexOf("/dp/");
	    	  if(-1 != index0) {
		    	  index1 = url.indexOf("/", index0 + "/dp/".length());
		    	  if(-1 != index1) {
		    		  item.setItemId(url.substring(index0 + "/dp/".length(), index1));
		    	  }
		      }
	      }
	      
	      /*XPath xpath = new DefaultXPath(COO8_ITEM_ID_XPATH);
		  Object node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setItemId(((org.dom4j.Node)node).getText());
		  }*/
		  
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
		  /*String html = content.toString();
		  int start = html.indexOf("\"hiResImage\":");
		  if(-1 != start) {
			  int end = html.indexOf(",", start);
			  if(-1 != end) {
				  item.setLogoURL(html.substring(start + "\"hiResImage\":".length() + "\"".length(), end - "\"".length()));
			  }
		  }*/
		  xpath = new DefaultXPath(AMAZON_ITEM_PHOTO_XPATH);  
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setLogoURL(generatePhoto(((org.dom4j.Attribute)node).getValue()));
		  } else {
			  xpath = new DefaultXPath(AMAZON_ITEM_PHOTO_XPATH_1);  
			  node = xpath.selectSingleNode(document);
			  if(null != node) {
				  item.setLogoURL(generatePhoto(((org.dom4j.Attribute)node).getValue()));
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
		  item.setStatus(ItemStatusEnum.ITEM_STATUS_ON_SALE.getValue());
		  item.setFreightFeePayer(FreightFeePayerEnum.FREIGHT_FEE_PALYER_SELLER.getValue());
		  item.setIsDeleted(false);
		  
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
  
  static final String GOME_ITEM_ID_XPATH = "/HTML/BODY/DIV[4]/DIV[1]/DIV[3]/DIV[2]/text()";
  static final String GOME_ITEM_NAME_XPATH = "//*[@id='lxf-sctc']/DIV[1]/DIV[2]/P";
  static final String GOME_ITEM_PRICE_XPATH = "/HTML/BODY/DIV[4]/DIV[1]/DIV[3]/DIV[3]/B";
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
		  item.setStatus(ItemStatusEnum.ITEM_STATUS_ON_SALE.getValue());
		  item.setFreightFeePayer(FreightFeePayerEnum.FREIGHT_FEE_PALYER_SELLER.getValue());
		  item.setIsDeleted(false);
		  
		  //System.out.println(content.toString());
		  /*Writer writer = null;
		  try {
			  writer = new BufferedWriter(new FileWriter("/home/sihai/test.html"));
			  writer.write(content.toString());
			  writer.flush();
		  } catch (IOException e) {
			  e.printStackTrace();
		  } finally{
			  if(null != writer) {
				  try {
					  writer.close();
				  } catch (IOException e) {
					  e.printStackTrace();
				  }
			  }
		  }*/
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
			  item.setItemId(((org.dom4j.Node)node).getText());
		  }
		  
	      // itemName
		  xpath = new DefaultXPath(GOME_ITEM_NAME_XPATH);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  String txt = ((org.dom4j.Node)node).getText();
			  item.setName(txt.substring(0, txt.indexOf("已成功加入收藏")).trim());
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
  static final String SUNING_ITEM_PRICE_XPATH = "//*[@id='tellMe']/xmlns:A[1]/@href";
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
		  item.setStatus(ItemStatusEnum.ITEM_STATUS_ON_SALE.getValue());
		  item.setFreightFeePayer(FreightFeePayerEnum.FREIGHT_FEE_PALYER_SELLER.getValue());
		  item.setIsDeleted(false);
		  
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
	      // itemId
	      String url = content.getUrl();
	      int index0 = url.indexOf("/emall/");
	      if(-1 != index0) {
	    	  int index1 = url.indexOf(".html");
	    	  if(-1 != index1) {
	    		  item.setItemId(url.substring(index0 + "/emall/".length(), index1));
	    	  }
	      }
	      
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
		 xpath = new DefaultXPath(SUNING_ITEM_PRICE_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(getSuningPrice(((org.dom4j.Node)node).getText()));
		  }
		  
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

  static final String NEW_EGG_ITEM_NAME_XPATH = "//*[@id='proCtner']/xmlns:DIV[1]/xmlns:H1";
  static final String NEW_EGG_ITEM_CATEGORY_XPATH = "//*[@id='crumb']/xmlns:DIV/xmlns:A";
  static final String NEW_EGG_ITEM_PHOTO_XPATH = "//*[@id='thumbnails1']/xmlns:DIV/xmlns:UL/xmlns:LI[1]/xmlns:A/xmlns:IMG/@ref2";
//*[@id="proMainInfo"]/div[2]/div[1]/dl/dd[4]/p[1]/img
  static final String NEW_EGG_ITEM_PRICE_XPATH = "//*[@id='proMainInfo']/xmlns:DIV[2]/xmlns:DIV[1]/xmlns:DL/xmlns:DD[4]/xmlns:P[1]/xmlns:IMG/@src";
  static final String NEW_EGG_ITEM_PROMOTION_PRICE_XPATH = "//*[@id='proMainInfo']/xmlns:DIV[2]/xmlns:DIV[1]/xmlns:DL/xmlns:DD[5]/xmlns:P[1]/xmlns:IMG/@src";
  
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
		  item.setStatus(ItemStatusEnum.ITEM_STATUS_ON_SALE.getValue());
		  item.setFreightFeePayer(FreightFeePayerEnum.FREIGHT_FEE_PALYER_SELLER.getValue());
		  item.setIsDeleted(false);
		  
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
	      
	      String url = content.getUrl();
	      int index0 = url.indexOf("/Product/");
	      if(-1 != index0) {
	    	  int index1 = url.indexOf(".htm", index0 + "/Product/".length());
	    	  if(-1 != index1) {
	    		  item.setItemId(url.substring(index0 + "/Product/".length(), index1));
	    	  }
	      }
	      
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
		  /*xpath = new DefaultXPath(NEW_EGG_ITEM_PRICE_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(DOMContentUtils.discernNewEggPrice((((org.dom4j.Node)node).getText())));
		  }*/
		  
		  // TODO 优惠价格
		  xpath = new DefaultXPath(NEW_EGG_ITEM_PROMOTION_PRICE_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(DOMContentUtils.discernNewEggPrice((((org.dom4j.Node)node).getText())));
		  } else {
			  // 拿新蛋价格
			  xpath = new DefaultXPath(NEW_EGG_ITEM_PRICE_XPATH);
			  xpath.setNamespaceContext(context);
			  node = xpath.selectSingleNode(document);
			  if(null != node) {
				  item.setPrice(DOMContentUtils.discernNewEggPrice((((org.dom4j.Node)node).getText())));
			  }
		  }
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

  static final String NO1_SHOP_ITEM_NAME_XPATH = "//*[@id='productMainName']";
  static final String NO1_SHOP_ITEM_CATEGORY_XPATH = "/xmlns:HTML/xmlns:BODY/xmlns:DIV[5]/xmlns:DIV[1]/xmlns:SPAN/xmlns:A";
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
		  item.setStatus(ItemStatusEnum.ITEM_STATUS_ON_SALE.getValue());
		  item.setFreightFeePayer(FreightFeePayerEnum.FREIGHT_FEE_PALYER_SELLER.getValue());
		  item.setIsDeleted(false);
		  
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
	      
	      String url = content.getUrl();
	      int index0 = url.indexOf("/product/");
	      if(-1 != index0) {
	    	  item.setItemId(url.substring(index0 + "/product/".length(), url.length()));
	      }
	      
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
  
  static final String FIVE_I_BUY_ITEM_ID_XPATH = "//*[@id='container']/DIV[2]/DIV[2]/H1/SPAN";
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
		  item.setStatus(ItemStatusEnum.ITEM_STATUS_ON_SALE.getValue());
		  item.setFreightFeePayer(FreightFeePayerEnum.FREIGHT_FEE_PALYER_SELLER.getValue());
		  item.setIsDeleted(false);
		  
		  //System.out.println(content.toString());
		  byte[] contentInOctets = content.getContent();
	      InputSource input = new InputSource(new ByteArrayInputStream(contentInOctets));
	      DOMParser parser = new DOMParser();
	      parser.parse(input);
	      org.w3c.dom.Document w3cDoc = parser.getDocument(); 
	      DOMReader domReader = new DOMReader();
	      org.dom4j.Document document = domReader.read(w3cDoc);
	      
	      // itemId
	      XPath xpath = new DefaultXPath(FIVE_I_BUY_ITEM_ID_XPATH);
		  Object node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setItemId(((org.dom4j.Node)node).getText().substring("产品编号：".length()));
		  }
	      
	      // itemName
	      xpath = new DefaultXPath(FIVE_1_BUY_ITEM_NAME_XPATH);
		  node = xpath.selectSingleNode(document);
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
 
  static final String LUSEN_ITEM_NAME_XPATH = "/xmlns:HTML/xmlns:BODY/xmlns:DIV[1]/xmlns:DIV[7]/xmlns:DIV[3]/xmlns:DIV[2]/xmlns:DIV[1]/xmlns:DIV[2]";
  static final String LUSEN_ITEM_CATEGORY_XPATH = "/xmlns:HTML/xmlns:BODY/xmlns:DIV[1]/xmlns:DIV[7]/xmlns:DIV[1]/xmlns:DIV[2]/xmlns:A";
  static final String LUSEN_ITEM_PHOTO_XPATH = "//*[@id='smallPic']/@lazy_src";
  static final String LUSEN_ITEM_PRICE_XPATH = "//*[@id='DivProducInfo']/xmlns:DIV[2]/xmlns:SPAN[2]/xmlns:FONT";
  static final String LUSEN_ITEM_PROMOTION_PRICE_XPATH = "//*[@id='DivPanicBuyOrComity']/xmlns:DIV[2]/xmlns:SPAN[2]/xml:FONT";
  static final String LUSEN_ITEM_GIFT_XPATH = "";
  
  /**
   * 
   * @param content
   * @return
   */
  public static ItemDO getLusenItem(Content content) {
	  try {
		  ItemDO item = new ItemDO();
		  item.setPlatform(PlatformEnum.PLATFORM_LUSEN.getValue());
		  item.setShop(MatrixBridge.getFixedShop(PlatformEnum.PLATFORM_LUSEN));
		  item.setDetailURL(content.getUrl());
		  item.setStuffStatus(StuffStatusEnum.STUFF_NEW.getValue());
		  item.setNumber(-1L);
		  item.setStatus(ItemStatusEnum.ITEM_STATUS_ON_SALE.getValue());
		  item.setFreightFeePayer(FreightFeePayerEnum.FREIGHT_FEE_PALYER_SELLER.getValue());
		  item.setIsDeleted(false);
		  
		  //System.out.println(content.toString());
		  /*Writer writer = null;
		  try {
			  writer = new BufferedWriter(new FileWriter("/home/sihai/test.html"));
			  writer.write(content.toString());
			  writer.flush();
		  } catch (IOException e) {
			  e.printStackTrace();
		  } finally{
			  if(null != writer) {
				  try {
					  writer.close();
				  } catch (IOException e) {
					  e.printStackTrace();
				  }
			  }
		  }*/
		  
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
	      item.setItemId(URLUtil.getParameter(content.getUrl(), "id"));
	      
	      // itemName
	      XPath xpath = new DefaultXPath(LUSEN_ITEM_NAME_XPATH);
		  xpath.setNamespaceContext(context);
	      Object node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setName(((org.dom4j.Node)node).getText());
		  }
		  
	      // category
		  List<String> categoryPath = new ArrayList<String>(3);
		  xpath = new DefaultXPath(LUSEN_ITEM_CATEGORY_XPATH);
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
		  xpath = new DefaultXPath(LUSEN_ITEM_PRICE_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(Double.valueOf(((org.dom4j.Node)node).getText().substring("￥".length()).replaceAll(",", "")));
		  }
		  
		  // TODO 优惠价格, 如果有优惠价格, 将会覆盖上面的价格
		  xpath = new DefaultXPath(LUSEN_ITEM_PROMOTION_PRICE_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(Double.valueOf(((org.dom4j.Node)node).getText().substring("￥".length()).replaceAll(",", "")));
		  }
		  
		  // TODO photo
		  xpath = new DefaultXPath(LUSEN_ITEM_PHOTO_XPATH);
		  xpath.setNamespaceContext(context);
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
  
  static final String EFEIHU_ITEM_ID_XPATH = "//*[@id='itemId_no']";
  static final String EFEIHU_ITEM_NAME_XPATH = "//*[@id='itemName']/xmlns:H2";
  static final String EFEIHU_ITEM_CATEGORY_XPATH = "//*[@id='ctl00_ContentPlaceHolder1_ctl10_div1']/xmlns:LI/xmlns:A/text()";
  static final String EFEIHU_ITEM_PHOTO_XPATH = "//*[@id='smallimg_list']/xmlns:LI[1]/xmlns:DIV/xmlns:A/xmlns:IMG/@src";
  static final String EFEIHU_ITEM_PRICE_XPATH = "//*[@id='dom_sale_price']";
  static final String EFEIHU_ITEM_PROMOTION_PRICE_XPATH = "//*[@id='dom_sale_price']";
  static final String EFEIHU_ITEM_GIFT_XPATH = "";
  
  /**
   * 
   * @param content
   * @return
   */
  public static ItemDO getEfeihuItem(Content content) {
	  try {
		  ItemDO item = new ItemDO();
		  item.setPlatform(PlatformEnum.PLATFORM_EFEIHU.getValue());
		  item.setShop(MatrixBridge.getFixedShop(PlatformEnum.PLATFORM_EFEIHU));
		  item.setDetailURL(content.getUrl());
		  item.setStuffStatus(StuffStatusEnum.STUFF_NEW.getValue());
		  item.setNumber(-1L);
		  item.setStatus(ItemStatusEnum.ITEM_STATUS_ON_SALE.getValue());
		  item.setFreightFeePayer(FreightFeePayerEnum.FREIGHT_FEE_PALYER_SELLER.getValue());
		  item.setIsDeleted(false);
		  
		  //System.out.println(content.toString());
		  /*Writer writer = null;
		  try {
			  writer = new BufferedWriter(new FileWriter("/home/sihai/test.html"));
			  writer.write(new String(content.getContent(), "gbk"));
			  writer.flush();
		  } catch (IOException e) {
			  e.printStackTrace();
		  } finally{
			  if(null != writer) {
				  try {
					  writer.close();
				  } catch (IOException e) {
					  e.printStackTrace();
				  }
			  }
		  }*/
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
	      XPath xpath = new DefaultXPath(EFEIHU_ITEM_ID_XPATH);
	      xpath.setNamespaceContext(context);
		  Object node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setItemId(((org.dom4j.Node)node).getText());
		  }
	      
	      // itemName
	      xpath = new DefaultXPath(EFEIHU_ITEM_NAME_XPATH);
		  xpath.setNamespaceContext(context);
	      node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setName(((org.dom4j.Node)node).getText());
		  }
		  
	      // category
		  List<String> categoryPath = new ArrayList<String>(3);
		  xpath = new DefaultXPath(EFEIHU_ITEM_CATEGORY_XPATH);
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
		  xpath = new DefaultXPath(EFEIHU_ITEM_PRICE_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(Double.valueOf(((org.dom4j.Node)node).getText().substring("￥".length()).replaceAll(",", "")));
		  }
		  
		  // TODO 优惠价格, 如果有优惠价格, 将会覆盖上面的价格 NO Need
		 /* xpath = new DefaultXPath(EFEIHU_ITEM_PROMOTION_PRICE_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(Double.valueOf(((org.dom4j.Node)node).getText().substring("￥".length()).replaceAll(",", "")));
		  }*/
		  
		  // TODO photo
		  xpath = new DefaultXPath(EFEIHU_ITEM_PHOTO_XPATH);
		  xpath.setNamespaceContext(context);
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

  static final String TAO3C_ITEM_ID_XPATH = "//*[@id='mainright']/xmlns:DIV/xmlns:DIV[3]/xmlns:DIV[1]/xmlns:DIV[1]";
  static final String TAO3C_ITEM_NAME_XPATH = "//*[@id='mainright']/xmlns:DIV/xmlns:DIV[1]";
  static final String TAO3C_ITEM_CATEGORY_XPATH = "//*[@id='main']/xmlns:DIV[1]/xmlns:A";
  static final String TAO3C_ITEM_PHOTO_XPATH = "//*[@id='midImg_0']/@src";
  static final String TAO3C_ITEM_PRICE_XPATH = "//*[@id='rm1_3']/xmlns:DIV/xmlns:SPAN/xmlns:CITE";
  static final String TAO3C_ITEM_PROMOTION_PRICE_XPATH = "//*[@id='dom_sale_price']";
  static final String TAO3C_ITEM_GIFT_XPATH = "";
  
  /**
   * 
   * @param content
   * @return
   */
  public static ItemDO getTao3cItem(Content content) {
	  try {
		  ItemDO item = new ItemDO();
		  item.setPlatform(PlatformEnum.PLATFORM_TAO3C.getValue());
		  item.setShop(MatrixBridge.getFixedShop(PlatformEnum.PLATFORM_TAO3C));
		  item.setDetailURL(content.getUrl());
		  item.setStuffStatus(StuffStatusEnum.STUFF_NEW.getValue());
		  item.setNumber(-1L);
		  item.setStatus(ItemStatusEnum.ITEM_STATUS_ON_SALE.getValue());
		  item.setFreightFeePayer(FreightFeePayerEnum.FREIGHT_FEE_PALYER_SELLER.getValue());
		  item.setIsDeleted(false);
		  
		  //System.out.println(content.toString());
		  /*Writer writer = null;
		  try {
			  writer = new BufferedWriter(new FileWriter("/home/sihai/test.html"));
			  writer.write(new String(content.getContent(), "gbk"));
			  writer.flush();
		  } catch (IOException e) {
			  e.printStackTrace();
		  } finally{
			  if(null != writer) {
				  try {
					  writer.close();
				  } catch (IOException e) {
					  e.printStackTrace();
				  }
			  }
		  }*/
		  
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
	      XPath xpath = new DefaultXPath(TAO3C_ITEM_ID_XPATH);
	      xpath.setNamespaceContext(context);
		  Object node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setItemId(((org.dom4j.Node)node).getText());
		  }
	      
	      // itemName
	      xpath = new DefaultXPath(TAO3C_ITEM_NAME_XPATH);
		  xpath.setNamespaceContext(context);
	      node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setName(((org.dom4j.Node)node).getText().substring("商品编号：".length()));
		  }
		  
	      // category
		  List<String> categoryPath = new ArrayList<String>(3);
		  xpath = new DefaultXPath(TAO3C_ITEM_CATEGORY_XPATH);
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
		  /*xpath = new DefaultXPath(TAO3C_ITEM_PRICE_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(Double.valueOf(((org.dom4j.Node)node).getText().substring("￥".length()).replaceAll(",", "")));
		  }*/
		  
		  // get price
		  String html = new String(content.getContent(), "gbk");
		  int index1 = html.indexOf("<span>￥<cite>");
		  if(-1 != index1) {
			  int index2 = html.indexOf("</cite></span>", index1);
			  if(-1 != index2) {
				  item.setPrice(Double.valueOf(html.substring(index1 + "<span>￥<cite>".length(), index2).replaceAll(",", "")));
			  }
		  }
		  
		  // TODO 优惠价格, 如果有优惠价格, 将会覆盖上面的价格 NO Need
		 /* xpath = new DefaultXPath(EFEIHU_ITEM_PROMOTION_PRICE_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(Double.valueOf(((org.dom4j.Node)node).getText().substring("￥".length()).replaceAll(",", "")));
		  }*/
		  
		  // TODO photo
		  xpath = new DefaultXPath(TAO3C_ITEM_PHOTO_XPATH);
		  xpath.setNamespaceContext(context);
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
  
  static final String OUKU_ITEM_ID_XPATH = "";
  static final String OUKU_ITEM_NAME_XPATH = "//*[@id='goods_left']/xmlns:DIV[1]/xmlns:DIV/xmlns:DIV[2]/xmlns:UL[1]/xmlns:LI[1]/xmlns:DIV[1]/xmlns:H2";
  static final String OUKU_ITEM_CATEGORY_XPATH = "//*[@id='crumbs']/xmlns:A";
  static final String OUKU_ITEM_PHOTO_XPATH = "//*[@id='showPicBig']/@src";
  static final String OUKU_ITEM_PRICE_XPATH = "//*[@id='shopPrice']";
  static final String OUKU_ITEM_PROMOTION_PRICE_XPATH = "//*[@id='dom_sale_price']";
  static final String OUKU_ITEM_GIFT_XPATH = "";
  
  /**
   * 
   * @param content
   * @return
   */
  public static ItemDO getOukuItem(Content content) {
	  try {
		  ItemDO item = new ItemDO();
		  item.setPlatform(PlatformEnum.PLATFORM_OUKU.getValue());
		  item.setShop(MatrixBridge.getFixedShop(PlatformEnum.PLATFORM_OUKU));
		  item.setDetailURL(content.getUrl());
		  item.setStuffStatus(StuffStatusEnum.STUFF_NEW.getValue());
		  item.setNumber(-1L);
		  item.setStatus(ItemStatusEnum.ITEM_STATUS_ON_SALE.getValue());
		  item.setFreightFeePayer(FreightFeePayerEnum.FREIGHT_FEE_PALYER_SELLER.getValue());
		  item.setIsDeleted(false);
		  
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
	      String url = content.getUrl();
	      int index0 = url.indexOf("/goods");
	      if(-1 != index0) {
	    	  item.setItemId(url.substring(index0 + "/".length(), url.length()));
	      }
	      
	      // itemName
	      XPath xpath = new DefaultXPath(OUKU_ITEM_NAME_XPATH);
		  xpath.setNamespaceContext(context);
	      Object node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setName(((org.dom4j.Node)node).getText());
		  }
		  
	      // category
		  List<String> categoryPath = new ArrayList<String>(3);
		  xpath = new DefaultXPath(OUKU_ITEM_CATEGORY_XPATH);
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
		  xpath = new DefaultXPath(OUKU_ITEM_PRICE_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  String str = ((org.dom4j.Node)node).getText();
			  item.setPrice(Double.valueOf(str.substring(str.indexOf("￥") + "￥".length()).replaceAll(",", "")));
		  }
		  
		  // TODO 优惠价格, 如果有优惠价格, 将会覆盖上面的价格 NO Need
		 /* xpath = new DefaultXPath(EFEIHU_ITEM_PROMOTION_PRICE_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(Double.valueOf(((org.dom4j.Node)node).getText().substring("￥".length()).replaceAll(",", "")));
		  }*/
		  
		  // TODO photo
		  xpath = new DefaultXPath(OUKU_ITEM_PHOTO_XPATH);
		  xpath.setNamespaceContext(context);
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
  
  static final String NEW7_ITEM_ID_XPATH = "/xmlns:HTML/xmlns:BODY/xmlns:DIV[4]/xmlns:DIV[2]/xmlns:DL[1]/xmlns:DD";
  static final String NEW7_ITEM_NAME_XPATH = "/xmlns:HTML/xmlns:BODY/xmlns:DIV[3]/text()";
  static final String NEW7_ITEM_CATEGORY_XPATH = "/xmlns:HTML/xmlns:BODY/xmlns:DIV[3]/xmlns:A";
  static final String NEW7_ITEM_PHOTO_XPATH = "//*[@id='list']/xmlns:DIV[1]/xmlns:A/xmlns:IMG/@jqimg";
  static final String NEW7_ITEM_PRICE_XPATH = "/xmlns:HTML/xmlns:BODY/xmlns:DIV[4]/xmlns:DIV[2]/xmlns:DL[2]/xmlns:DD/text()";
  static final String NEW7_ITEM_PROMOTION_PRICE_XPATH = "//*[@id='dom_sale_price']";
  static final String NEW7_ITEM_GIFT_XPATH = "";
  
  /**
   * 
   * @param content
   * @return
   */
  public static ItemDO getNew7Item(Content content) {
	  try {
		  ItemDO item = new ItemDO();
		  item.setPlatform(PlatformEnum.PLATFORM_NEW7.getValue());
		  item.setShop(MatrixBridge.getFixedShop(PlatformEnum.PLATFORM_NEW7));
		  item.setDetailURL(content.getUrl());
		  item.setStuffStatus(StuffStatusEnum.STUFF_NEW.getValue());
		  item.setNumber(-1L);
		  item.setStatus(ItemStatusEnum.ITEM_STATUS_ON_SALE.getValue());
		  item.setFreightFeePayer(FreightFeePayerEnum.FREIGHT_FEE_PALYER_SELLER.getValue());
		  item.setIsDeleted(false);
		  
		  //System.out.println(content.toString());
		 /* Writer writer = null;
		  try {
			  writer = new BufferedWriter(new FileWriter("/home/sihai/test.html"));
			  writer.write(content.toString());
			  writer.flush();
		  } catch (IOException e) {
			  e.printStackTrace();
		  } finally{
			  if(null != writer) {
				  try {
					  writer.close();
				  } catch (IOException e) {
					  e.printStackTrace();
				  }
			  }
		  }*/
		  
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
	      XPath xpath = new DefaultXPath(NEW7_ITEM_ID_XPATH);
		  xpath.setNamespaceContext(context);
	      Object node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setItemId(((org.dom4j.Node)node).getText());
		  }
	      
	      // itemName
	      xpath = new DefaultXPath(NEW7_ITEM_NAME_XPATH);
		  xpath.setNamespaceContext(context);
	      node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setName(((org.dom4j.Node)node).getText());
		  }
		  
	      // category
		  List<String> categoryPath = new ArrayList<String>(3);
		  xpath = new DefaultXPath(NEW7_ITEM_CATEGORY_XPATH);
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
		  xpath = new DefaultXPath(NEW7_ITEM_PRICE_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  String str = ((org.dom4j.Node)node).getText();
			  item.setPrice(Double.valueOf(str.replaceAll(",", "")));
		  }
		  
		  // TODO 优惠价格, 如果有优惠价格, 将会覆盖上面的价格 NO Need
		 /* xpath = new DefaultXPath(EFEIHU_ITEM_PROMOTION_PRICE_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(Double.valueOf(((org.dom4j.Node)node).getText().substring("￥".length()).replaceAll(",", "")));
		  }*/
		  
		  // TODO photo
		  xpath = new DefaultXPath(NEW7_ITEM_PHOTO_XPATH);
		  xpath.setNamespaceContext(context);
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
  
  static final String RED_BABY_ITEM_ID_XPATH = "//*[@id='commonBasicInfo']/xmlns:UL/xmlns:LI[1]";
  static final String RED_BABY_ITEM_NAME_XPATH = "//*[@id='pName']/xmlns:H1/text()";
  static final String RED_BABY_ITEM_CATEGORY_XPATH = "/xmlns:HTML/xmlns:BODY/xmlns:DIV[2]/xmlns:DIV[1]/xmlns:DL/xmlns:DD/xmlns:A";
  static final String RED_BABY_ITEM_PHOTO_XPATH = "//*[@id='jqzoomDiv']/xmlns:IMG/@jqimg";
  static final String RED_BABY_ITEM_PRICE_XPATH = "//*[@id='price']";
  static final String RED_BABY_ITEM_PROMOTION_PRICE_XPATH = "//*[@id='specP']/xmlns:DIV[2]/xmlns:SPAN[2]";
  static final String RED_BABY_ITEM_GIFT_XPATH = "";
  
  /**
   * 
   * @param content
   * @return
   */
  public static ItemDO getRedBabyItem(Content content) {
	  try {
		  ItemDO item = new ItemDO();
		  item.setPlatform(PlatformEnum.PLATFORM_RED_BABY.getValue());
		  item.setShop(MatrixBridge.getFixedShop(PlatformEnum.PLATFORM_RED_BABY));
		  item.setDetailURL(content.getUrl());
		  item.setStuffStatus(StuffStatusEnum.STUFF_NEW.getValue());
		  item.setNumber(-1L);
		  item.setStatus(ItemStatusEnum.ITEM_STATUS_ON_SALE.getValue());
		  item.setFreightFeePayer(FreightFeePayerEnum.FREIGHT_FEE_PALYER_SELLER.getValue());
		  item.setIsDeleted(false);
		  
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
	      XPath xpath = new DefaultXPath(RED_BABY_ITEM_ID_XPATH);
		  xpath.setNamespaceContext(context);
	      Object node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setItemId(((org.dom4j.Node)node).getText());
		  }
	      
	      // itemName
	      xpath = new DefaultXPath(RED_BABY_ITEM_NAME_XPATH);
		  xpath.setNamespaceContext(context);
	      node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setName(((org.dom4j.Node)node).getText());
		  }
		  
	      // category
		  List<String> categoryPath = new ArrayList<String>(3);
		  xpath = new DefaultXPath(RED_BABY_ITEM_CATEGORY_XPATH);
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
		  // get price
		  item.setPrice(getRedBabyPrice(item.getItemId()));
		  
		  
		  xpath = new DefaultXPath(RED_BABY_ITEM_PRICE_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(Double.valueOf(((org.dom4j.Node)node).getText().replaceAll(",", "")));
		  }
		  
		  // TODO 优惠价格, 如果有优惠价格, 将会覆盖上面的价格 NO Need
		  xpath = new DefaultXPath(RED_BABY_ITEM_PROMOTION_PRICE_XPATH);
		  xpath.setNamespaceContext(context);
		  node = xpath.selectSingleNode(document);
		  if(null != node) {
			  item.setPrice(Double.valueOf(((org.dom4j.Node)node).getText().replaceAll(",", "")));
		  }
		  
		  // TODO photo
		  xpath = new DefaultXPath(RED_BABY_ITEM_PHOTO_XPATH);
		  xpath.setNamespaceContext(context);
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
   
  public static String getJingdongCategory(Node node) {
	  return null;
  }
  
  public static Double discernJingdongPrice(String photoURL) {
	  System.out.println(String.format("Price photo url:%s", photoURL));
	  File tmpFile = null;
      try {
    	  tmpFile = getFile(photoURL, ".png");
          String result = instance.doOCR(tmpFile);
          if(result.startsWith("Y")) {
        	  return Double.valueOf(result.substring("Y".length()));
          } else if(result.startsWith("51")) {
        	  return Double.valueOf(result.substring("51".length()));
          } else {
        	  return Double.valueOf(result);
          }
      } catch (TesseractException e) {
          logger.error(e);
      } finally {
    	  if(null != tmpFile) {
    		  tmpFile.delete();
    	  }
      }
	  return null;
  }

  public static Double discernCoo8Price(String photoURL) {
	  System.out.println(String.format("Price photo url:%s", photoURL));
	  File tmpFile = null;
      try {
    	  tmpFile = getFile(photoURL, ".png");
          String result = instance.doOCR(tmpFile);
          return Double.valueOf(result.substring("¥".length()));
      } catch (TesseractException e) {
    	  logger.error(e);
      } finally {
    	  if(null != tmpFile) {
    		  tmpFile.delete();
    	  }
      }
	  return null;
  }
  
  public static Double discernNewEggPrice(String photoURL) {
	  System.out.println(String.format("Price photo url:%s", photoURL));
	  File tmpFile = null;
      try {
    	  tmpFile = getFile(photoURL, ".gif");
          String result = instance.doOCR(tmpFile);
          return Double.valueOf(result);
      } catch (TesseractException e) {
    	  logger.error(e);
      } finally {
    	  if(null != tmpFile) {
    		  tmpFile.delete();
    	  }
      }
	  return null;
  }
  
  public static Double getSuningPrice(String html) {
	  String[] kvs = html.split("&");
	  String[] kv = null;
	  for(String s : kvs) {
		  if(s.contains("currPrice")) {
			  kv = s.split("=");
			  return Double.valueOf(kv[1]);
		  }
	  }
	  
	  return null;
  }
  
  public static Double getRedBabyPrice(String itemId) {
	  try {
		  Double price = null;
		  String strURL = "http://plus.redbaby.com.cn/plus/product/getPriceInfo?pId=" + itemId;
		  Protocol protocol = protocolFactory.getProtocol(strURL);
		  ProtocolOutput output = protocol.getProtocolOutput(new Text(strURL), new CrawlDatum());
		  String html = output.getContent().toString();
		  
		  // 尝试取特价(会员价和特价都有)
		  int index1 = html.indexOf("<span class='font_red bigRed'>");
		  int index2 = -1;
		  if(-1 != index1) {
			  index2 = html.indexOf("</span>", index1 + "<span class='font_red bigRed'>".length());
			  if(-1 != index2) {
				  price = Double.valueOf(html.substring(index1 + "<span class='font_red bigRed'>".length(), index2).replaceAll(",", ""));
			  }
		  }
		  
		  // 只有会员价
		  if(null == price) {
			  index1 = html.indexOf("<span class='font_red bigRed' id='price'>");
			  if(-1 != index1) {
				  index2 = html.indexOf("</span>", index1 + "<span class='font_red bigRed' id='price'>".length());
				  if(-1 != index2) {
					  price = Double.valueOf(html.substring(index1 + "<span class='font_red bigRed' id='price'>".length(), index2).replaceAll(",", ""));
				  }
			  }
		  }
		  
		  return price;
	  } catch (ProtocolNotFound e) {
		  logger.error("Not prossiable");
		  return null;
	  }
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
  
  public static File getFile(String strURL, String suffix) {
	  BufferedOutputStream out = null;
	  try {
		  Protocol protocol = protocolFactory.getProtocol(strURL);
		  ProtocolOutput output = protocol.getProtocolOutput(new Text(strURL), new CrawlDatum());
		  URL url = new URL(strURL);
		  String fileName = url.getFile();
		  if(StringUtil.isEmpty(fileName)) {
			  return null;
		  }
		  File tmpFile = File.createTempFile("matrix_price_file", suffix);
		  tmpFile.deleteOnExit();
		  out = new BufferedOutputStream(new FileOutputStream(tmpFile));
		  out.write(output.getContent().getContent(), 0, output.getContent().getContent().length);
		  out.flush();
		  return tmpFile;
	  } catch (ProtocolNotFound e) {
		  logger.error("Not prossiable");
		  return null;
	  } catch (MalformedURLException e) {
			logger.error(String.format("Wrong url:%s", strURL), e);
	  } catch (IOException e) { 
			logger.error(String.format("Read url:%s or write content to file failed: ", strURL), e);
	  }  finally {
			if(null != out) {
				try {
					out.close();
				} catch (IOException e) {
					logger.error(e);
				}
			}
	  }
	  return null;
  }
  
  public static void main(String[] args) {
	  
	  System.out.println(discernJingdongPrice("http://jprice.360buyimg.com/price/gp306412-1-1-3.png"));
	  System.out.println(discernCoo8Price("http://price.51mdq.com/iprice/218/218041,4.png"));
	  System.out.println(discernNewEggPrice("http://www.newegg.com.cn/Common/PriceImage.aspx?PId=zVGJOBSvSac%3d"));
	  
	  /*try {
		  String result = instance.doOCR(new File("/home/sihai/matrix_price_file3420572322911722107.png"));
      	  System.out.println(result);
		  result = instance.doOCR(new File("/home/sihai/matrix_price_file6289927822260068121.png"));
      	  System.out.println(result);
      	  result = instance.doOCR(new File("/home/sihai/matrix_price_file6359705014357507256.gif"));
      	  System.out.println(result);
	  } catch (TesseractException e) {
		  e.printStackTrace();
	  }*/
  }
}

