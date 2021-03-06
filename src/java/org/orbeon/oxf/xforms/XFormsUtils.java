/**
 * Copyright (C) 2010 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.pool.PoolableObjectFactory;
import org.apache.log4j.Logger;
import org.ccil.cowan.tagsoup.HTMLSchema;
import org.dom4j.*;
import org.dom4j.io.DocumentSource;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.pipeline.api.ExternalContext;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.processor.DebugProcessor;
import org.orbeon.oxf.resources.URLFactory;
import org.orbeon.oxf.util.*;
import org.orbeon.oxf.xforms.control.controls.XFormsOutputControl;
import org.orbeon.oxf.xforms.control.controls.XXFormsAttributeControl;
import org.orbeon.oxf.xforms.event.events.XFormsLinkErrorEvent;
import org.orbeon.oxf.xforms.processor.XFormsServer;
import org.orbeon.oxf.xforms.xbl.XBLBindings;
import org.orbeon.oxf.xforms.xbl.XBLContainer;
import org.orbeon.oxf.xml.*;
import org.orbeon.oxf.xml.XMLUtils;
import org.orbeon.oxf.xml.dom4j.Dom4jUtils;
import org.orbeon.oxf.xml.dom4j.LocationData;
import org.orbeon.oxf.xml.dom4j.LocationDocumentResult;
import org.orbeon.oxf.xml.dom4j.LocationDocumentSource;
import org.orbeon.saxon.Configuration;
import org.orbeon.saxon.dom4j.NodeWrapper;
import org.orbeon.saxon.functions.FunctionLibrary;
import org.orbeon.saxon.om.*;
import org.orbeon.saxon.value.*;
import org.orbeon.saxon.value.StringValue;
import org.w3c.tidy.Tidy;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.TransformerHandler;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;

public class XFormsUtils {

    private static final String LOGGING_CATEGORY = "utils";
    private static final Logger logger = LoggerFactory.createLogger(XFormsUtils.class);
    private static final IndentedLogger indentedLogger = XFormsContainingDocument.getIndentedLogger(logger, XFormsServer.getLogger(), LOGGING_CATEGORY);

    private static final int SRC_CONTENT_BUFFER_SIZE = 1024;

    // Binary types supported for upload, images, etc.
    private static final Map<String, String> SUPPORTED_BINARY_TYPES = new HashMap<String, String>();

    static {
        SUPPORTED_BINARY_TYPES.put(XMLConstants.XS_BASE64BINARY_EXPLODED_QNAME, "base64Binary");
        SUPPORTED_BINARY_TYPES.put(XMLConstants.XS_ANYURI_EXPLODED_QNAME, "anyURI");
        SUPPORTED_BINARY_TYPES.put(XFormsConstants.XFORMS_BASE64BINARY_EXPLODED_QNAME, "base64Binary");
        SUPPORTED_BINARY_TYPES.put(XFormsConstants.XFORMS_ANYURI_EXPLODED_QNAME, "anyURI");
    }

    /**
     * Iterate through nodes of the instance document and call the walker on each of them.
     *
     * @param instance          instance to iterate
     * @param instanceWalker    walker to call back
     * @param allNodes          all the nodes, otherwise only leaf data nodes
     */
    public static void iterateInstanceData(XFormsInstance instance, InstanceWalker instanceWalker, boolean allNodes) {
        iterateInstanceData(instance.getInstanceRootElementInfo(), instanceWalker, allNodes);
    }

    private static void iterateInstanceData(NodeInfo elementNodeInfo, InstanceWalker instanceWalker, boolean allNodes) {

        final List childrenElements = getChildrenElements(elementNodeInfo);

        // We "walk" an element which contains elements only if allNodes == true
        if (allNodes || childrenElements.size() == 0)
            instanceWalker.walk(elementNodeInfo);

        // "walk" current element's attributes
        for (Object o: getAttributes(elementNodeInfo)) {
            final NodeInfo attributeNodeInfo = (NodeInfo) o;
            instanceWalker.walk(attributeNodeInfo);
        }
        // "walk" current element's children elements
        if (childrenElements.size() != 0) {
            for (Object childrenElement: childrenElements) {
                final NodeInfo childElement = (NodeInfo) childrenElement;
                iterateInstanceData(childElement, instanceWalker, allNodes);
            }
        }
    }

    public static String encodeXMLAsDOM(PipelineContext pipelineContext, org.w3c.dom.Node node) {
        try {
            return encodeXML(pipelineContext, TransformerUtils.domToDom4jDocument(node), XFormsProperties.getXFormsPassword(), false);
        } catch (TransformerException e) {
            throw new OXFException(e);
        }
    }

    public static String encodeXML(PropertyContext propertyContext, Document documentToEncode, boolean encodeLocationData) {
        return encodeXML(propertyContext, documentToEncode, XFormsProperties.getXFormsPassword(), encodeLocationData);
    }

    // Use a Deflater pool as creating deflaters is expensive
    private static final SoftReferenceObjectPool DEFLATER_POOL = new SoftReferenceObjectPool(new DeflaterPoolableObjectFactory());

    public static String encodeXML(PropertyContext propertyContext, Document documentToEncode, String encryptionPassword, boolean encodeLocationData) {
        //        XFormsServer.logger.debug("XForms - encoding XML.");

        // Get SAXStore
        // TODO: This is not optimal since we create a second in-memory representation. Should stream instead.
        final SAXStore saxStore;
        try {
            saxStore = new SAXStore();
            final SAXResult saxResult = new SAXResult(saxStore);
            final Transformer identity = TransformerUtils.getIdentityTransformer();
            final Source source = encodeLocationData ? new LocationDocumentSource(documentToEncode) : new DocumentSource(documentToEncode);
            identity.transform(source, saxResult);
        } catch (TransformerException e) {
            throw new OXFException(e);
        }

        // Serialize SAXStore to bytes
        // TODO: This is not optimal since we create a third in-memory representation. Should stream instead.
        final byte[] bytes;
        try {
            final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            saxStore.writeExternal(new ObjectOutputStream(byteArrayOutputStream));
            bytes = byteArrayOutputStream.toByteArray();
        } catch (IOException e) {
            throw new OXFException(e);
        }

        // Encode bytes
        return encodeBytes(propertyContext, bytes, encryptionPassword);
    }

    public static String encodeBytes(PropertyContext propertyContext, byte[] bytesToEncode, String encryptionPassword) {
        Deflater deflater = null;
        try {
            // Compress if needed
            final byte[] gzipByteArray;
            if (XFormsProperties.isGZIPState()) {
                deflater = (Deflater) DEFLATER_POOL.borrowObject();
                final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                final DeflaterGZIPOutputStream gzipOutputStream = new DeflaterGZIPOutputStream(deflater, byteArrayOutputStream, 1024);
                gzipOutputStream.write(bytesToEncode);
                gzipOutputStream.close();
                gzipByteArray = byteArrayOutputStream.toByteArray();
            } else {
                gzipByteArray = null;
            }

            // Encrypt if needed
            if (encryptionPassword != null) {
                // Perform encryption
                if (gzipByteArray == null) {
                    // The data was not compressed above
                    return "X1" + SecureUtils.encrypt(propertyContext, encryptionPassword, bytesToEncode);
                } else {
                    // The data was compressed above
                    return "X2" + SecureUtils.encrypt(propertyContext, encryptionPassword, gzipByteArray);
                }
            } else {
                // No encryption
                if (gzipByteArray == null) {
                    // The data was not compressed above
                    return "X3" + Base64.encode(bytesToEncode, false);
                } else {
                    // The data was compressed above
                    return "X4" + Base64.encode(gzipByteArray, false);
                }
            }
        } catch (Throwable e) {
            try {
                if (deflater != null)
                    DEFLATER_POOL.invalidateObject(deflater);
            } catch (Exception e1) {
                throw new OXFException(e1);
            }
            throw new OXFException(e);
        } finally {
            try {
                if (deflater != null) {
                    deflater.reset();
                    DEFLATER_POOL.returnObject(deflater);
                }
            } catch (Exception e) {
                throw new OXFException(e);
            }
        }
    }

    public static String ensureEncrypted(PropertyContext propertyContext, String encoded) {
        if (encoded.startsWith("X3") || encoded.startsWith("X4")) {
            // Data is currently not encrypted, so encrypt it
            final byte[] decodedValue = XFormsUtils.decodeBytes(propertyContext, encoded, XFormsProperties.getXFormsPassword());
            return XFormsUtils.encodeBytes(propertyContext, decodedValue, XFormsProperties.getXFormsPassword());
        } else {
            // Data is already encrypted
            return encoded;
        }

//        if (encoded.startsWith("X1") || encoded.startsWith("X2")) {
//            // Case where data is already encrypted
//            return encoded;
//        } else if (encoded.startsWith("X3")) {
//            // Uncompressed data to encrypt
//            final byte[] decoded = Base64.decode(encoded.substring(2));
//            return "X1" + SecureUtils.encrypt(pipelineContext, encryptionPassword, decoded).replace((char) 0xa, ' ');
//        } else if (encoded.startsWith("X4")) {
//            // Compressed data to encrypt
//            final byte[] decoded = Base64.decode(encoded.substring(2));
//            return "X2" + SecureUtils.encrypt(pipelineContext, encryptionPassword, decoded).replace((char) 0xa, ' ');
//        } else {
//            throw new OXFException("Invalid prefix for encoded data: " + encoded.substring(0, 2));
//        }
    }

    public static org.w3c.dom.Document htmlStringToDocument(String value, LocationData locationData) {
        // Create and configure Tidy instance
        final Tidy tidy = new Tidy();
        tidy.setShowWarnings(false);
        tidy.setQuiet(true);
        tidy.setInputEncoding("utf-8");
        //tidy.setNumEntities(true); // CHECK: what does this do exactly?

        // Parse and output to SAXResult
        final byte[] valueBytes;
        try {
            valueBytes = value.getBytes("utf-8");
        } catch (UnsupportedEncodingException e) {
            throw new OXFException(e); // will not happen
        }
        try {
            final InputStream is = new ByteArrayInputStream(valueBytes);
            return tidy.parseDOM(is, null);
        } catch (Exception e) {
            throw new ValidationException("Cannot parse value as text/html for value: '" + value + "'", locationData);
        }
    }

    private static void htmlStringToResult(String value, LocationData locationData, Result result) {
        try {
            final XMLReader xmlReader = new org.ccil.cowan.tagsoup.Parser();
            final HTMLSchema theSchema = new HTMLSchema();
            xmlReader.setProperty(org.ccil.cowan.tagsoup.Parser.schemaProperty, theSchema);
            xmlReader.setFeature(org.ccil.cowan.tagsoup.Parser.ignoreBogonsFeature, true);
            final TransformerHandler identity = TransformerUtils.getIdentityTransformerHandler();
            identity.setResult(result);
            xmlReader.setContentHandler(identity);
            final InputSource inputSource = new InputSource();
            inputSource.setCharacterStream(new StringReader(value));
            xmlReader.parse(inputSource);
        } catch (Exception e) {
            throw new ValidationException("Cannot parse value as text/html for value: '" + value + "'", locationData);
        }
//			r.setFeature(Parser.CDATAElementsFeature, false);
//			r.setFeature(Parser.namespacesFeature, false);
//			r.setFeature(Parser.ignoreBogonsFeature, true);
//			r.setFeature(Parser.bogonsEmptyFeature, false);
//			r.setFeature(Parser.defaultAttributesFeature, false);
//			r.setFeature(Parser.translateColonsFeature, true);
//			r.setFeature(Parser.restartElementsFeature, false);
//			r.setFeature(Parser.ignorableWhitespaceFeature, true);
//			r.setProperty(Parser.scannerProperty, new PYXScanner());
//          r.setProperty(Parser.lexicalHandlerProperty, h);
    }


    public static org.w3c.dom.Document htmlStringToDocumentTagSoup(String value, LocationData locationData) {
        final org.w3c.dom.Document document = XMLUtils.createDocument();
        final DOMResult domResult = new DOMResult(document);
        htmlStringToResult(value, locationData, domResult);
        return document;
    }

    public static Document htmlStringToDom4jTagSoup(String value, LocationData locationData) {
        final LocationDocumentResult documentResult = new LocationDocumentResult();
        htmlStringToResult(value, locationData, documentResult);
        return documentResult.getDocument();
    }

    // TODO: implement server-side plain text output with <br> insertion
//    public static void streamPlainText(final ContentHandler contentHandler, String value, LocationData locationData, final String xhtmlPrefix) {
//        // 1: Split string along 0x0a and remove 0x0d (?)
//        // 2: Output string parts, and between them, output <xhtml:br> element
//        try {
//            contentHandler.characters(filteredValue.toCharArray(), 0, filteredValue.length());
//        } catch (SAXException e) {
//            throw new OXFException(e);
//        }
//    }

    public static void streamHTMLFragment(ContentHandler contentHandler, String value, LocationData locationData, String xhtmlPrefix) {
        
        if (value != null && value.trim().length() > 0) { // don't parse blank values

//            final boolean useTagSoup = false;
//
//            if (useTagSoup) {
//                try {
//                    final XMLReader xmlReader = new org.ccil.cowan.tagsoup.Parser();
//		            final HTMLSchema theSchema = new HTMLSchema();
//
//                    xmlReader.setProperty(org.ccil.cowan.tagsoup.Parser.schemaProperty, theSchema);
//                    xmlReader.setContentHandler(new HTMLBodyContentHandler(contentHandler, xhtmlPrefix));
//
//                    final InputSource inputSource = new InputSource();
//                    inputSource.setCharacterStream(new StringReader(value));
//
//                    xmlReader.parse(inputSource);
//                } catch (SAXException e) {
//                    throw new OXFException(e);
//                } catch (IOException e) {
//                    throw new OXFException(e);
//                }
//
////			r.setFeature(Parser.CDATAElementsFeature, false);
////			r.setFeature(Parser.namespacesFeature, false);
////			r.setFeature(Parser.ignoreBogonsFeature, true);
////			r.setFeature(Parser.bogonsEmptyFeature, false);
////			r.setFeature(Parser.defaultAttributesFeature, false);
////			r.setFeature(Parser.translateColonsFeature, true);
////			r.setFeature(Parser.restartElementsFeature, false);
////			r.setFeature(Parser.ignorableWhitespaceFeature, true);
////			r.setProperty(Parser.scannerProperty, new PYXScanner());
////          r.setProperty(Parser.lexicalHandlerProperty, h);
//
//            } else {

                final org.w3c.dom.Document htmlDocument = htmlStringToDocument(value, locationData);

                // Stream fragment to the output
                try {
                    if (htmlDocument != null) {
                        final Transformer identity = TransformerUtils.getIdentityTransformer();
                        identity.transform(new DOMSource(htmlDocument),
                                new SAXResult(new HTMLBodyContentHandler(contentHandler, xhtmlPrefix)));
                    }
                } catch (TransformerException e) {
                    throw new OXFException(e);
                }
//            }
        }
    }

    /**
     * Get the value of a child element known to have only static content.
     *
     * @param childElement          element to evaluate (xforms:label, etc.)
     * @param acceptHTML            whether the result may contain HTML
     * @param containsHTML          whether the result actually contains HTML (null allowed)
     * @return                      string containing the result of the evaluation, null if evaluation failed
     */
    public static String getStaticChildElementValue(final Element childElement, final boolean acceptHTML, final boolean[] containsHTML) {
        // Check that there is a current child element
        if (childElement == null)
            return null;

        // No HTML found by default
        if (containsHTML != null)
            containsHTML[0] = false;

        // Try to get inline value
        {
            final StringBuilder sb = new StringBuilder(20);

            // Visit the subtree and serialize

            // NOTE: It is a little funny to do our own serialization here, but the alternative is to build a DOM and
            // serialize it, which is not trivial because of the possible interleaved xforms:output's. Furthermore, we
            // perform a very simple serialization of elements and text to simple (X)HTML, not full-fledged HTML or XML
            // serialization.

            Dom4jUtils.visitSubtree(childElement, new LHHAElementVisitorListener(acceptHTML, containsHTML, sb, childElement));
            if (acceptHTML && containsHTML != null && !containsHTML[0]) {
                // We went through the subtree and did not find any HTML
                // If the caller supports the information, return a non-escaped string so we can optimize output later
                return XMLUtils.unescapeXMLMinimal(sb.toString());
            } else {
                // We found some HTML, just return it
                return sb.toString();
            }
        }
    }

    /**
     * Get the value of a child element by pushing the context of the child element on the binding stack first, then
     * calling getElementValue() and finally popping the binding context.
     *
     * @param propertyContext       current context
     * @param container             current XFormsContainingDocument
     * @param sourceEffectiveId     source effective id for id resolution
     * @param scope                 XBL scope
     * @param childElement          element to evaluate (xforms:label, etc.)
     * @param acceptHTML            whether the result may contain HTML
     * @param containsHTML          whether the result actually contains HTML (null allowed)
     * @return                      string containing the result of the evaluation, null if evaluation failed
     */
    public static String getChildElementValue(final PropertyContext propertyContext, final XBLContainer container, final String sourceEffectiveId,
                                              final XBLBindings.Scope scope, final Element childElement, final boolean acceptHTML, boolean[] containsHTML) {

        // Check that there is a current child element
        if (childElement == null)
            return null;

        // Child element becomes the new binding
        final XFormsContextStack contextStack = container.getContextStack();
        contextStack.pushBinding(propertyContext, childElement, sourceEffectiveId, scope);
        final String result = getElementValue(propertyContext, container, contextStack, sourceEffectiveId, childElement, acceptHTML, containsHTML);
        contextStack.popBinding();
        return result;
    }

    /**
     * Get the value of an element by trying single-node binding, value attribute, linking attribute, and inline value
     * (including nested XHTML and xforms:output elements).
     *
     * This may return an HTML string if HTML is accepted and found, or a plain string otherwise.
     *
     * @param propertyContext       current PipelineContext
     * @param container             current XBLContainer
     * @param contextStack          context stack for XPath evaluation
     * @param sourceEffectiveId     source effective id for id resolution
     * @param childElement          element to evaluate (xforms:label, etc.)
     * @param acceptHTML            whether the result may contain HTML
     * @param containsHTML          whether the result actually contains HTML (null allowed)
     * @return                      string containing the result of the evaluation, null if evaluation failed
     */
    public static String getElementValue(final PropertyContext propertyContext, final XBLContainer container,
                                         final XFormsContextStack contextStack, final String sourceEffectiveId,
                                         final Element childElement, final boolean acceptHTML, final boolean[] containsHTML) {

        // No HTML found by default
        if (containsHTML != null)
            containsHTML[0] = false;

        final XFormsContextStack.BindingContext currentBindingContext = contextStack.getCurrentBindingContext();

        // "the order of precedence is: single node binding attributes, linking attributes, inline text."

        // Try to get single node binding
        {
            final boolean hasSingleNodeBinding = currentBindingContext.isNewBind();
            if (hasSingleNodeBinding) {
                final Item boundItem = currentBindingContext.getSingleItem();
                final String tempResult = XFormsUtils.getBoundItemValue(boundItem);
                if (tempResult != null) {
                    return (acceptHTML && containsHTML == null) ? XMLUtils.escapeXMLMinimal(tempResult) : tempResult;
                } else
                    return null;
            }
        }

        // Try to get value attribute
        // NOTE: This is an extension attribute not standard in XForms 1.0 or 1.1
        {
            final String valueAttribute = childElement.attributeValue("value");
            final boolean hasValueAttribute = valueAttribute != null;
            if (hasValueAttribute) {
                final List<Item> currentNodeset = currentBindingContext.getNodeset();
                if (currentNodeset != null && currentNodeset.size() > 0) {
                    final String tempResult = XPathCache.evaluateAsString(propertyContext,
                            currentNodeset, currentBindingContext.getPosition(),
                            valueAttribute, container.getNamespaceMappings(childElement),
                            contextStack.getCurrentVariables(), XFormsContainingDocument.getFunctionLibrary(),
                            contextStack.getFunctionContext(sourceEffectiveId), null,
                            (LocationData) childElement.getData());

                    contextStack.returnFunctionContext();

                    return (acceptHTML && containsHTML == null) ? XMLUtils.escapeXMLMinimal(tempResult) : tempResult;
                } else {
                    return null;
                }
            }
        }

        // Try to get linking attribute
        // NOTE: This is deprecated in XForms 1.1
        {
            final String srcAttributeValue = childElement.attributeValue("src");
            final boolean hasSrcAttribute = srcAttributeValue != null;
            if (hasSrcAttribute) {
                try {
                    // NOTE: This should probably be cached, but on the other hand almost nobody uses @src
                    final String tempResult  = retrieveSrcValue(srcAttributeValue);
                    if (containsHTML != null)
                        containsHTML[0] = false; // NOTE: we could support HTML if the media type returned is text/html
                    return (acceptHTML && containsHTML == null) ? XMLUtils.escapeXMLMinimal(tempResult) : tempResult;
                } catch (IOException e) {
                    // Dispatch xforms-link-error to model
                    final XFormsModel currentModel = currentBindingContext.model;
                    // NOTE: xforms-link-error is no longer in XForms 1.1 starting 2009-03-10
                    currentModel.getXBLContainer(null).dispatchEvent(propertyContext, new XFormsLinkErrorEvent(container.getContainingDocument(), currentModel, srcAttributeValue, childElement, e));
                    return null;
                }
            }
        }

        // Try to get inline value
        {
            final StringBuilder sb = new StringBuilder(20);

            // Visit the subtree and serialize

            // NOTE: It is a little funny to do our own serialization here, but the alternative is to build a DOM and
            // serialize it, which is not trivial because of the possible interleaved xforms:output's. Furthermore, we
            // perform a very simple serialization of elements and text to simple (X)HTML, not full-fledged HTML or XML
            // serialization.
            Dom4jUtils.visitSubtree(childElement, new LHHAElementVisitorListener(propertyContext, container, contextStack,
                    sourceEffectiveId, acceptHTML, containsHTML, sb, childElement));
            if (acceptHTML && containsHTML != null && !containsHTML[0]) {
                // We went through the subtree and did not find any HTML
                // If the caller supports the information, return a non-escaped string so we can optimize output later
                return XMLUtils.unescapeXMLMinimal(sb.toString());
            } else {
                // We found some HTML, just return it
                return sb.toString();
            }
        }
    }

    /**
     * Compare two objects, handling null values as well.
     *
     * @param value1    first value or null
     * @param value2    second value or null
     * @return          whether the values are identical or both null
     */
    public static boolean compareStrings(Object value1, Object value2) {
        return (value1 == null && value2 == null) || (value1 != null && value2 != null && value1.equals(value2));
    }

    /**
     * Compare two collections, handling null values as well.
     *
     * @param value1    first value or null
     * @param value2    second value or null
     * @return          whether the values are identical or both null
     */
    public static boolean compareCollections(Collection value1, Collection value2) {
        // Add quick check on size, which AbstractList e.g. doesn't do
        return (value1 == null && value2 == null) || (value1 != null && value2 != null && value1.size() == value2.size() && value1.equals(value2));
    }

    /**
     * Compare two maps, handling null values as well.
     *
     * @param value1    first value or null
     * @param value2    second value or null
     * @return          whether the values are identical or both null
     */
    public static boolean compareMaps(Map value1, Map value2) {
        return (value1 == null && value2 == null) || (value1 != null && value2 != null && value1.equals(value2));
    }

    public static ValueRepresentation convertJavaObjectToSaxonObject(Object object) {
        final ValueRepresentation valueRepresentation;
        if (object instanceof ValueRepresentation) {
            // Native Saxon variable value
            valueRepresentation = (ValueRepresentation) object;
        } else if (object instanceof String) {
            valueRepresentation = new StringValue((String) object);
        } else if (object instanceof Boolean) {
            valueRepresentation = BooleanValue.get((Boolean) object);
        } else if (object instanceof Integer) {
            valueRepresentation = new Int64Value((Integer) object);
        } else if (object instanceof Float) {
            valueRepresentation = new FloatValue((Float) object);
        } else if (object instanceof Double) {
            valueRepresentation = new DoubleValue((Double) object);
        } else if (object instanceof URI) {
            valueRepresentation = new AnyURIValue(object.toString());
        } else {
            throw new OXFException("Invalid variable type: " + object.getClass());
        }
        return valueRepresentation;
    }

    private static class DeflaterPoolableObjectFactory implements PoolableObjectFactory {
        public Object makeObject() throws Exception {
            indentedLogger.logDebug("", "creating new Deflater");
            return new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        }

        public void destroyObject(Object object) throws Exception {
        }

        public boolean validateObject(Object object) {
            return true;
        }

        public void activateObject(Object object) throws Exception {
        }

        public void passivateObject(Object object) throws Exception {
        }
    }

    private static class DeflaterGZIPOutputStream extends DeflaterOutputStream {
        public DeflaterGZIPOutputStream(Deflater deflater, OutputStream out, int size) throws IOException {
            super(out, deflater, size);
            writeHeader();
            crc.reset();
        }

        private boolean closed = false;
        protected CRC32 crc = new CRC32();
        private final static int GZIP_MAGIC = 0x8b1f;
        private final static int TRAILER_SIZE = 8;

        public synchronized void write(byte[] buf, int off, int len) throws IOException {
            super.write(buf, off, len);
            crc.update(buf, off, len);
        }

        public void finish() throws IOException {
            if (!def.finished()) {
                def.finish();
                while (!def.finished()) {
                    int len = def.deflate(buf, 0, buf.length);
                    if (def.finished() && len <= buf.length - TRAILER_SIZE) {
                        writeTrailer(buf, len);
                        len = len + TRAILER_SIZE;
                        out.write(buf, 0, len);
                        return;
                    }
                    if (len > 0)
                        out.write(buf, 0, len);
                }
                byte[] trailer = new byte[TRAILER_SIZE];
                writeTrailer(trailer, 0);
                out.write(trailer);
            }
        }

        public void close() throws IOException {
            if (!closed) {
                finish();
                out.close();
                closed = true;
            }
        }

        private final static byte[] header = {
                (byte) GZIP_MAGIC,
                (byte) (GZIP_MAGIC >> 8),
                Deflater.DEFLATED,
                0,
                0,
                0,
                0,
                0,
                0,
                0
        };

        private void writeHeader() throws IOException {
            out.write(header);
        }

        private void writeTrailer(byte[] buf, int offset) {
            writeInt((int) crc.getValue(), buf, offset);
            writeInt(def.getTotalIn(), buf, offset + 4);
        }

        private void writeInt(int i, byte[] buf, int offset) {
            writeShort(i & 0xffff, buf, offset);
            writeShort((i >> 16) & 0xffff, buf, offset + 2);
        }

        private void writeShort(int s, byte[] buf, int offset) {
            buf[offset] = (byte) (s & 0xff);
            buf[offset + 1] = (byte) ((s >> 8) & 0xff);
        }
    }

    public static org.w3c.dom.Document decodeXMLAsDOM(PipelineContext pipelineContext, String encodedXML) {
        try {
            return TransformerUtils.dom4jToDomDocument(XFormsUtils.decodeXML(pipelineContext, encodedXML));
        } catch (TransformerException e) {
            throw new OXFException(e);
        }
    }

    public static Document decodeXML(PropertyContext propertyContext, String encodedXML) {
        return decodeXML(propertyContext, encodedXML, XFormsProperties.getXFormsPassword());
    }

    public static Document decodeXML(PropertyContext propertyContext, String encodedXML, String encryptionPassword) {

        final byte[] bytes = decodeBytes(propertyContext, encodedXML, encryptionPassword);

        // Deserialize bytes to SAXStore
        // TODO: This is not optimal
        final ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);
        final SAXStore saxStore;
        try {
            saxStore = new SAXStore(new ObjectInputStream(byteArrayInputStream));
        } catch (IOException e) {
            throw new OXFException(e);
        }

        // Deserialize SAXStore to dom4j document
        // TODO: This is not optimal
        final TransformerHandler identity = TransformerUtils.getIdentityTransformerHandler();
        final LocationDocumentResult result = new LocationDocumentResult();
        identity.setResult(result);
        try {
            saxStore.replay(identity);
        } catch (SAXException e) {
            throw new OXFException(e);
        }
        return result.getDocument();
    }

//    public static String decodeString(PipelineContext pipelineContext, String encoded) {
//        try {
//            return new String(decodeBytes(pipelineContext, encoded,  getEncryptionKey()), "utf-8");
//        } catch (UnsupportedEncodingException e) {
//            throw new OXFException(e);// won't happen
//        }
//    }

    public static byte[] decodeBytes(PropertyContext propertyContext, String encoded, String encryptionPassword) {
        try {
            // Get raw text
            byte[] resultBytes;
            {
                final String prefix = encoded.substring(0, 2);
                final String encodedString = encoded.substring(2);

                final byte[] resultBytes1;
                final byte[] gzipByteArray;
                if (prefix.equals("X1")) {
                    // Encryption + uncompressed
                    resultBytes1 = SecureUtils.decrypt(propertyContext, encryptionPassword, encodedString);
                    gzipByteArray = null;
                } else if (prefix.equals("X2")) {
                    // Encryption + compressed
                    resultBytes1 = null;
                    gzipByteArray = SecureUtils.decrypt(propertyContext, encryptionPassword, encodedString);
                } else if (prefix.equals("X3")) {
                    // No encryption + uncompressed
                    resultBytes1 = Base64.decode(encodedString);
                    gzipByteArray = null;
                } else if (prefix.equals("X4")) {
                    // No encryption + compressed
                    resultBytes1 = null;
                    gzipByteArray = Base64.decode(encodedString);
                } else {
                    throw new OXFException("Invalid prefix for encoded string: " + prefix);
                }

                // Decompress if needed
                if (gzipByteArray != null) {
                    final ByteArrayInputStream compressedData = new ByteArrayInputStream(gzipByteArray);
                    final GZIPInputStream gzipInputStream = new GZIPInputStream(compressedData);
                    final ByteArrayOutputStream binaryData = new ByteArrayOutputStream(1024);
                    NetUtils.copyStream(gzipInputStream, binaryData);
                    resultBytes = binaryData.toByteArray();
                } else {
                    resultBytes = resultBytes1;
                }
            }
            return resultBytes;

        } catch (IOException e) {
            throw new OXFException(e);
        }
    }

    public static String retrieveSrcValue(String src) throws IOException {

        // Handle HHRI
        src = NetUtils.encodeHRRI(src, true);

        final URL url = URLFactory.createURL(src);

        // Load file into buffer
        // TODO: this is wrong, must use regular URL resolution methods
        final InputStreamReader reader = new InputStreamReader(url.openStream());
        try {
            final StringBuffer value = new StringBuffer();
            final char[] buff = new char[SRC_CONTENT_BUFFER_SIZE];
            int c;
            while ((c = reader.read(buff, 0, SRC_CONTENT_BUFFER_SIZE - 1)) != -1) value.append(buff, 0, c);
            return value.toString();
        } finally {
            reader.close();
        }
    }

    /**
     * Convert a value used for xforms:upload depending on its type. If the local name of the current type and the new
     * type are the same, return the value as passed. Otherwise, convert to or from anyURI and base64Binary.
     *
     * @param propertyContext   current context
     * @param value             value to convert
     * @param currentType       current type as exploded QName
     * @param newType           new type as exploded QName
     * @return                  converted value, or value passed
     */
    public static String convertUploadTypes(PropertyContext propertyContext, String value, String currentType, String newType) {

        final String currentTypeLocalName = SUPPORTED_BINARY_TYPES.get(currentType);
        if (currentTypeLocalName == null)
            throw new UnsupportedOperationException("Unsupported type: " + currentType);
        final String newTypeLocalName = SUPPORTED_BINARY_TYPES.get(newType);
        if (newTypeLocalName == null)
            throw new UnsupportedOperationException("Unsupported type: " + newType);

        if (currentTypeLocalName.equals(newTypeLocalName))
            return value;

        if (currentTypeLocalName.equals("base64Binary")) {
            // Convert from xs:base64Binary or xforms:base64Binary to xs:anyURI or xforms:anyURI
            // TODO: remove cast to PipelineContext
            return NetUtils.base64BinaryToAnyURI((PipelineContext) propertyContext, value, NetUtils.REQUEST_SCOPE);
        } else {
            // Convert from xs:anyURI or xforms:anyURI to xs:base64Binary or xforms:base64Binary
            return NetUtils.anyURIToBase64Binary(value);
        }
    }

    /**
     * Get the external context from the property context.
     *
     * @param propertyContext   current context
     * @return                  external context if found, null otherwise
     */
    public static ExternalContext getExternalContext(PropertyContext propertyContext) {
        return (ExternalContext) propertyContext.getAttribute(PipelineContext.EXTERNAL_CONTEXT);
    }

    /**
     * Resolve a render URL including xml:base resolution.
     *
     * @param isPortletLoad         whether this is called within a portlet
     * @param propertyContext       current context
     * @param currentElement        element used for xml:base resolution
     * @param url                   URL to resolve
     * @return                      resolved URL
     */
    public static String resolveRenderURL(boolean isPortletLoad, PropertyContext propertyContext, Element currentElement, String url) {
        final URI resolvedURI = resolveXMLBase(currentElement, url);
        final String resolvedURIString = resolvedURI.toString();

        final String externalURL;
        // NOTE: Keep in mind that this is going to run from within a servlet, as the XForms server
        // runs in a servlet when processing these events!
        // TODO: is this the case with JSR-268? Don't/can't we run xforms-server in the portlet?
        if (!isPortletLoad) {
            // XForms page was loaded from a servlet
            externalURL = XFormsUtils.getExternalContext(propertyContext).getResponse().rewriteRenderURL(resolvedURIString, null, null);
        } else {
            // XForms page was loaded from a portlet
            if (resolvedURI.getFragment() != null) {
                // Remove fragment if there is one, as it doesn't serve in a portlet
                try {
                    externalURL = new URI(resolvedURI.getScheme(), resolvedURI.getAuthority(), resolvedURI.getPath(), resolvedURI.getQuery(), null).toString();
                } catch (URISyntaxException e) {
                    throw new OXFException(e);
                }
            } else {
                externalURL = resolvedURIString;
            }
        }

        return externalURL;
    }

    /**
     * Resolve a resource URL including xml:base resolution.
     *
     * @param propertyContext       current context
     * @param element               element used to start resolution (if null, no resolution takes place)
     * @param url                   URL to resolve
     * @param rewriteMode           rewrite mode (see ExternalContext.Response)
     * @return                      resolved URL
     */
    public static String resolveResourceURL(PropertyContext propertyContext, Element element, String url, int rewriteMode) {

        final URI resolvedURI = resolveXMLBase(element, url);

        return XFormsUtils.getExternalContext(propertyContext).getResponse().rewriteResourceURL(resolvedURI.toString(), rewriteMode);
    }

    /**
     * Resolve a resource URL including xml:base resolution.
     *
     * @param propertyContext       current PropertyContext
     * @param element               element used to start resolution (if null, no resolution takes place)
     * @param url                   URL to resolve
     * @param rewriteMode           rewrite mode (see ExternalContext.Response)
     * @return                      resolved URL
     */
    public static String resolveServiceURL(PropertyContext propertyContext, Element element, String url, int rewriteMode) {

        final URI resolvedURI = resolveXMLBase(element, url);

        return XFormsUtils.getExternalContext(propertyContext).rewriteServiceURL(resolvedURI.toString(), rewriteMode == ExternalContext.Response.REWRITE_MODE_ABSOLUTE);
    }

    /**
     * Rewrite an attribute if that attribute contains a URI, e.g. @href or @src.
     *
     * @param pipelineContext       current PipelineContext
     * @param containingDocument    current containing document
     * @param element               element used to start resolution (if null, no resolution takes place)
     * @param attributeName         attribute name
     * @param attributeValue        attribute value
     * @return                      rewritten URL
     */
    public static String getEscapedURLAttributeIfNeeded(PipelineContext pipelineContext, XFormsContainingDocument containingDocument, Element element, String attributeName, String attributeValue) {
        final String rewrittenValue;
        if ("src".equals(attributeName)) {
            rewrittenValue = resolveResourceURL(pipelineContext, element, attributeValue, ExternalContext.Response.REWRITE_MODE_ABSOLUTE_PATH_OR_RELATIVE);
        } else if ("href".equals(attributeName)) {

            // TODO: href may be an action URL or a render URL. Should pass element name and reuse code from AbstractRewrite.

            final boolean isPortletLoad = "portlet".equals(containingDocument.getContainerType());
            rewrittenValue = resolveRenderURL(isPortletLoad, pipelineContext, element, attributeValue);
        } else {
            rewrittenValue = attributeValue;
        }
        return rewrittenValue;
    }

    /**
     * Resolve attribute value templates (AVTs).
     *
     * @param propertyContext    current context
     * @param contextItems       context items
     * @param contextPosition    context position
     * @param variableToValueMap variables
     * @param functionLibrary    XPath function library to use
     * @param functionContext    context object to pass to the XForms function
     * @param prefixToURIMap     namespace mappings
     * @param locationData       LocationData for error reporting
     * @param attributeValue     attribute value
     * @return                   resolved attribute value
     */
    public static String resolveAttributeValueTemplates(PropertyContext propertyContext, List<Item> contextItems, int contextPosition, Map<String, ValueRepresentation> variableToValueMap,
                                                        FunctionLibrary functionLibrary, XPathCache.FunctionContext functionContext,
                                                        Map<String, String> prefixToURIMap, LocationData locationData, String attributeValue) {

        if (attributeValue == null)
            return null;

        return XPathCache.evaluateAsAvt(propertyContext, contextItems, contextPosition, attributeValue, prefixToURIMap,
                variableToValueMap, functionLibrary, functionContext, null, locationData);
    }

    /**
     * Resolve attribute value templates (AVTs).
     *
     * @param propertyContext   current context
     * @param xpathContext      current XPath context
     * @param contextNode       context node for evaluation
     * @param attributeValue    attribute value
     * @return                  resolved attribute value
     */
    public static String resolveAttributeValueTemplates(PropertyContext propertyContext, XPathCache.XPathContext xpathContext, NodeInfo contextNode, String attributeValue) {

        if (attributeValue == null)
            return null;

        return XPathCache.evaluateAsAvt(propertyContext, xpathContext, contextNode, attributeValue);
    }

    public static interface InstanceWalker {
        public void walk(NodeInfo nodeInfo);
    }

    /**
     * Resolve a URI string against an element, taking into account ancestor xml:base attributes for
     * the resolution.
     *
     * @param element   element used to start resolution (if null, no resolution takes place)
     * @param uri       URI to resolve
     * @return          resolved URI
     */
    public static URI resolveXMLBase(Element element, String uri) {
        try {
            // Allow for null Element
            if (element == null)
                return new URI(uri);

            final List<String> xmlBaseElements = new ArrayList<String>();

            // Collect xml:base values
            Element currentElement = element;
            do {
                final String xmlBaseAttribute = currentElement.attributeValue(XMLConstants.XML_BASE_QNAME);
                if (xmlBaseAttribute != null)
                    xmlBaseElements.add(xmlBaseAttribute);
                currentElement = currentElement.getParent();
            } while(currentElement != null);

            // Go from root to leaf
            Collections.reverse(xmlBaseElements);
            xmlBaseElements.add(uri);

            // Resolve paths from root to leaf
            URI result = null;
            for (final String currentXMLBase: xmlBaseElements) {
                final URI currentXMLBaseURI = new URI(currentXMLBase);
                result = (result == null) ? currentXMLBaseURI : result.resolve(currentXMLBaseURI);
            }
            return result;
        } catch (URISyntaxException e) {
            throw new ValidationException("Error while resolving URI: " + uri, e, (element != null) ? (LocationData) element.getData() : null);
        }
    }

    /**
     * Return an element's xml:base value, checking ancestors as well.
     *
     * @param element   element to check
     * @return          xml:base value or null if not found
     */
    public static String resolveXMLang(Element element) {
        // Allow for null Element
        if (element == null)
            return null;

        // Collect xml:base values
        Element currentElement = element;
        do {
            final String xmlBaseAttribute = currentElement.attributeValue(XMLConstants.XML_LANG_QNAME);
            if (xmlBaseAttribute != null)
                return xmlBaseAttribute;
            currentElement = currentElement.getParent();
        } while(currentElement != null);

        // Not found
        return null;
    }

    /**
     * Resolve f:url-norewrite attributes on this element, taking into account ancestor f:url-norewrite attributes for
     * the resolution.
     *
     * @param element   element used to start resolution
     * @return          true if rewriting is turned off, false otherwise
     */
    public static boolean resolveUrlNorewrite(Element element) {
        Element currentElement = element;
        do {
            final String urlNorewriteAttribute = currentElement.attributeValue(XMLConstants.FORMATTING_URL_NOREWRITE_QNAME);
            // Return the first ancestor value found
            if (urlNorewriteAttribute != null)
                return "true".equals(urlNorewriteAttribute);
            currentElement = currentElement.getParent();
        } while(currentElement != null);

        // Default is to rewrite
        return false;
    }

    /**
     * Log a message and Document.
     *
     * @param debugMessage  the message to display
     * @param document      the Document to display
     */
    public static void logDebugDocument(String debugMessage, Document document) {
        DebugProcessor.logger.info(debugMessage + ":\n" + Dom4jUtils.domToString(document));
    }

    /**
     * Prefix an id with the container namespace if needed. If the id is null, return null.
     *
     * @param containingDocument    current ContainingDocument
     * @param id                    id to prefix
     * @return                      prefixed id or null
     */
    public static String namespaceId(XFormsContainingDocument containingDocument, CharSequence id) {
        if (id == null)
            return null;
        else
            return containingDocument.getContainerNamespace() + id;
    }

    /**
     * Remove the container namespace prefix if possible. If the id is null, return null.
     *
     * @param containingDocument    current ContainingDocument
     * @param id                    id to de-prefix
     * @return                      de-prefixed id if possible or null
     */
    public static String deNamespaceId(XFormsContainingDocument containingDocument, String id) {
        if (id == null)
            return null;

        final String containerNamespace = containingDocument.getContainerNamespace();
        if (containerNamespace.length() > 0 && id.startsWith(containerNamespace))
            return id.substring(containerNamespace.length());
        else
            return id;
    }

    /**
     * Return LocationData for a given node, null if not found.
     *
     * @param node  node containing the LocationData
     * @return      LocationData or null
     */
    public static LocationData getNodeLocationData(Node node) {
        final Object data;
        {
            if (node instanceof Element)
                data = ((Element) node).getData();
            else if (node instanceof Attribute)
                data = ((Attribute) node).getData();
            else
                data = null;
            // TODO: other node types
        }
        if (data == null)
            return null;
        else if (data instanceof LocationData)
            return (LocationData) data;
        else if (data instanceof InstanceData)
            return ((InstanceData) data).getLocationData();

        return null;
    }

    public static Node getNodeFromNodeInfoConvert(NodeInfo nodeInfo, String errorMessage) {
        if (nodeInfo instanceof NodeWrapper)
            return getNodeFromNodeInfo(nodeInfo, errorMessage);
        else
            return TransformerUtils.tinyTreeToDom4j2((nodeInfo.getParent() instanceof DocumentInfo) ? nodeInfo.getParent() : nodeInfo);
    }

    /**
     * Return the underlying Node from the given NodeInfo if possible. If not, throw an exception with the given error
     * message.
     *
     * @param nodeInfo      NodeInfo to process
     * @param errorMessage  error message to throw
     * @return              Node if found
     */
    public static Node getNodeFromNodeInfo(NodeInfo nodeInfo, String errorMessage) {
        if (!(nodeInfo instanceof NodeWrapper))
            throw new OXFException(errorMessage);

        return (Node) ((NodeWrapper) nodeInfo).getUnderlyingNode();
    }

    /**
     * Get an element's children elements if any.
     *
     * @param nodeInfo  element NodeInfo to look at
     * @return          elements NodeInfo or empty list
     */
    public static List<NodeInfo> getChildrenElements(NodeInfo nodeInfo) {
        final List<NodeInfo> result = new ArrayList<NodeInfo>();
        getChildrenElements(result, nodeInfo);
        return result;
    }

    /**
     * Get an element's children elements if any.
     *
     * @param result    List to which to add the elements found
     * @param nodeInfo  element NodeInfo to look at
     */
    public static void getChildrenElements(List<NodeInfo> result, NodeInfo nodeInfo) {
        final AxisIterator i = nodeInfo.iterateAxis(Axis.CHILD);
        i.next();
        while (i.current() != null) {
            final Item current = i.current();
            if (current instanceof NodeInfo) {
                final NodeInfo currentNodeInfo = (NodeInfo) current;
                if (currentNodeInfo.getNodeKind() == org.w3c.dom.Document.ELEMENT_NODE) {
                    result.add(currentNodeInfo);
                }
            }
            i.next();
        }
    }

    /**
     * Return whether the given node has at least one child element.
     *
     * @param nodeInfo  NodeInfo to look at
     * @return          true iff NodeInfo has at least one child element
     */
    public static boolean hasChildrenElements(NodeInfo nodeInfo) {
        final AxisIterator i = nodeInfo.iterateAxis(Axis.CHILD);
        i.next();
        while (i.current() != null) {
            final Item current = i.current();
            if (current instanceof NodeInfo) {
                final NodeInfo currentNodeInfo = (NodeInfo) current;
                if (currentNodeInfo.getNodeKind() == org.w3c.dom.Document.ELEMENT_NODE) {
                    return true;
                }
            }
            i.next();
        }
        return false;
    }

    /**
     * Get an element's attributes if any.
     *
     * @param nodeInfo  element NodeInfo to look at
     * @return          attributes or empty list
     */
    public static List<Item> getAttributes(NodeInfo nodeInfo) {
        final List<Item> result = new ArrayList<Item>();
        getAttributes(result, nodeInfo);
        return result;
    }

    /**
     * Get an element's attributes if any.
     *
     * @param result    List to which to add the attributes found
     * @param nodeInfo  element NodeInfo to look at
     */
    public static void getAttributes(List<Item> result, NodeInfo nodeInfo) {

        if (nodeInfo.getNodeKind() != org.w3c.dom.Document.ELEMENT_NODE)
            throw new OXFException("Invalid node type passed to getAttributes(): " + nodeInfo.getNodeKind());

        final AxisIterator i = nodeInfo.iterateAxis(Axis.ATTRIBUTE);
        i.next();
        while (i.current() != null) {
            final Item current = i.current();
            if (current instanceof NodeInfo) {
                final NodeInfo currentNodeInfo = (NodeInfo) current;
                if (currentNodeInfo.getNodeKind() == org.w3c.dom.Document.ATTRIBUTE_NODE) {
                    result.add(currentNodeInfo);
                }
            }
            i.next();
        }
    }

    /**
     * Find all attributes and nested nodes of the given nodeset.
     */
    public static void getNestedAttributesAndElements(List<Item> result, List nodeset) {
        // Iterate through all nodes
        if (nodeset.size() > 0) {
            for (Object aNodeset: nodeset) {
                final NodeInfo currentNodeInfo = (NodeInfo) aNodeset;

                if (currentNodeInfo.getNodeKind() == org.w3c.dom.Document.ELEMENT_NODE) {
                    // Found an element

                    // Add attributes
                    getAttributes(result, currentNodeInfo);

                    // Find children elements
                    final List<NodeInfo> childrenElements = getChildrenElements(currentNodeInfo);

                    // Add all children elements
                    result.addAll(childrenElements);

                    // Recurse into children elements
                    getNestedAttributesAndElements(result, childrenElements);
                }
            }
        }
    }

    /**
     * Return whether the given string contains a well-formed XPath 2.0 expression.
     *
     * @param xpathString   string to check
     * @param namespaceMap  in-scope namespaces
     * @return              true iif the given string contains well-formed XPath 2.0
     */
    public static boolean isXPath2Expression(Configuration configuration, String xpathString, Map<String, String> namespaceMap) {
        // Empty string is never well-formed XPath
        if (xpathString.trim().length() == 0)
            return false;

        try {
            XPathCache.checkXPathExpression(configuration, xpathString, namespaceMap, XFormsContainingDocument.getFunctionLibrary());
        } catch (Exception e) {
            // Ideally we would like the parser to not throw as this is time-consuming, but not sure ho.w to achieve that
            return false;
        }

        return true;
    }

    /**
     * Create a JavaScript function name based on a script id.
     *
     * @param scriptId  id of the script
     * @return          JavaScript function name
     */
    public static String scriptIdToScriptName(String scriptId) {
        return scriptId.replace('-', '_').replace('$', '_') + "_xforms_function";
    }

    private static class LHHAElementVisitorListener implements Dom4jUtils.VisitorListener {
        private final PropertyContext pipelineContext;
        private final XBLContainer container;
        private final XFormsContextStack contextStack;
        private final String sourceEffectiveId;
        private final boolean acceptHTML;
        private final boolean[] containsHTML;
        private final StringBuilder sb;
        private final Element childElement;
        private final boolean hostLanguageAVTs;

        // Constructor for "static" case, i.e. when we know the child element cannot have dynamic content
        public LHHAElementVisitorListener(boolean acceptHTML, boolean[] containsHTML, StringBuilder sb, Element childElement) {
            this.pipelineContext = null;
            this.container = null;
            this.contextStack = null;
            this.sourceEffectiveId = null;
            this.acceptHTML = acceptHTML;
            this.containsHTML = containsHTML;
            this.sb = sb;
            this.childElement = childElement;
            this.hostLanguageAVTs = false;
        }

        // Constructor for "dynamic" case, i.e. when we know the child element can have dynamic content
        public LHHAElementVisitorListener(PropertyContext pipelineContext, XBLContainer container, XFormsContextStack contextStack,
                                          String sourceEffectiveId, boolean acceptHTML, boolean[] containsHTML, StringBuilder sb, Element childElement) {
            this.pipelineContext = pipelineContext;
            this.container = container;
            this.contextStack = contextStack;
            this.sourceEffectiveId = sourceEffectiveId;
            this.acceptHTML = acceptHTML;
            this.containsHTML = containsHTML;
            this.sb = sb;
            this.childElement = childElement;
            this.hostLanguageAVTs = XFormsProperties.isHostLanguageAVTs();
        }

        public void startElement(Element element) {
            if (element.getQName().equals(XFormsConstants.XFORMS_OUTPUT_QNAME)) {
                // This is an xforms:output nested among other markup

                // This can be null in "static" mode
                if (pipelineContext == null)
                    throw new OXFException("xforms:output must not show-up in static itemset: " + childElement.getName());

                final XFormsOutputControl outputControl = new XFormsOutputControl(container, null, element, element.getName(), null) {
                    // Override this as super.getContextStack() gets the containingDocument's stack, and here we need whatever is the current stack
                    // Probably need to modify super.getContextStack() at some point to NOT use the containingDocument's stack
                    @Override
                    protected XFormsContextStack getContextStack() {
                        return LHHAElementVisitorListener.this.contextStack;
                    }

                    @Override
                    public String getEffectiveId() {
                        // Return given source effective id, so we have a source effective id for resolution of index(), etc.
                        return sourceEffectiveId;
                    }
                };
                contextStack.pushBinding(pipelineContext, element, sourceEffectiveId, outputControl.getChildElementScope(element));
                {
                    outputControl.setBindingContext(pipelineContext, contextStack.getCurrentBindingContext());
                    outputControl.evaluate(pipelineContext);
                }
                contextStack.popBinding();

                if (outputControl.isRelevant()) {
                    if (acceptHTML) {
                        if ("text/html".equals(outputControl.getMediatype())) {
                            if (containsHTML != null)
                                containsHTML[0] = true; // this indicates for sure that there is some nested HTML
                            sb.append(outputControl.getExternalValue(pipelineContext));
                        } else {
                            // Mediatype is not HTML so we don't escape
                            sb.append(XMLUtils.escapeXMLMinimal(outputControl.getExternalValue(pipelineContext)));
                        }
                    } else {
                        if ("text/html".equals(outputControl.getMediatype())) {
                            // HTML is not allowed here, better tell the user
                            throw new OXFException("HTML not allowed within element: " + childElement.getName());
                        } else {
                            // Mediatype is not HTML so we don't escape
                            sb.append(outputControl.getExternalValue(pipelineContext));
                        }
                    }
                }
            } else {
                // This is a regular element, just serialize the start tag to no namespace

                // If HTML is not allowed here, better tell the user
                if (!acceptHTML)
                    throw new OXFException("Nested XHTML or XForms not allowed within element: " + childElement.getName());

                if (containsHTML != null)
                    containsHTML[0] = true;// this indicates for sure that there is some nested HTML

                sb.append('<');
                sb.append(element.getName());
                final List attributes = element.attributes();
                if (attributes.size() > 0) {
                    for (Object attribute: attributes) {
                        final Attribute currentAttribute = (Attribute) attribute;

                        final String currentAttributeName = currentAttribute.getName();
                        final String currentAttributeValue = currentAttribute.getValue();

                        final String resolvedValue;
                        if (hostLanguageAVTs && currentAttributeValue.indexOf('{') != -1) {
                            // This is an AVT, use attribute control to produce the output
                            final XXFormsAttributeControl attributeControl
                                    = new XXFormsAttributeControl(container, element, currentAttributeName, currentAttributeValue);

                            contextStack.pushBinding(pipelineContext, element, sourceEffectiveId, attributeControl.getChildElementScope(element));
                            {
                                attributeControl.setBindingContext(pipelineContext, contextStack.getCurrentBindingContext());
                                attributeControl.evaluate(pipelineContext);
                            }
                            contextStack.popBinding();

                            resolvedValue = attributeControl.getExternalValue(pipelineContext);
                        } else {
                            // Simply use control value
                            resolvedValue = currentAttributeValue;
                        }

                        // Only consider attributes in no namespace
                        if ("".equals(currentAttribute.getNamespaceURI())) {
                            sb.append(' ');
                            sb.append(currentAttributeName);
                            sb.append("=\"");
                            if (resolvedValue != null)
                                sb.append(XMLUtils.escapeXMLMinimal(resolvedValue));
                            sb.append('"');
                        }
                    }
                }
                sb.append('>');
            }
        }

        public void endElement(Element element) {
            if (!element.getQName().equals(XFormsConstants.XFORMS_OUTPUT_QNAME)) {
                // This is a regular element, just serialize the end tag to no namespace
                sb.append("</");
                sb.append(element.getName());
                sb.append('>');
            }
        }

        public void text(Text text) {
            sb.append(acceptHTML ? XMLUtils.escapeXMLMinimal(text.getStringValue()) : text.getStringValue());
        }
    }

    public static String escapeJavaScript(String value) {
        return StringUtils.replace(StringUtils.replace(StringUtils.replace(value, "\\", "\\\\"), "\"", "\\\""), "\n", "\\n");
    }

    /**
     * Return the prefix of an effective id, e.g. "" or "foo$bar$". The prefix returned does end with a separator.
     *
     * @param effectiveId   effective id to check
     * @return              prefix if any, "" if none, null if effectiveId was null
     */
    public static String getEffectiveIdPrefix(String effectiveId) {
        if (effectiveId == null)
            return null;

        final int prefixIndex = effectiveId.lastIndexOf(XFormsConstants.COMPONENT_SEPARATOR);
        if (prefixIndex != -1) {
            return effectiveId.substring(0, prefixIndex + 1);
        } else {
            return "";
        }
    }

    /**
     * Return the prefix of an effective id, e.g. "" or "foo$bar". The prefix returned does NOT end with a separator.
     *
     * @param effectiveId   effective id to check
     * @return              prefix if any, "" if none, null if effectiveId was null
     */
    public static String getEffectiveIdPrefixNoSeparator(String effectiveId) {
        if (effectiveId == null)
            return null;

        final int prefixIndex = effectiveId.lastIndexOf(XFormsConstants.COMPONENT_SEPARATOR);
        if (prefixIndex != -1) {
            return effectiveId.substring(0, prefixIndex);
        } else {
            return "";
        }
    }

    /**
     * Return whether the effective id has a suffix.
     *
     * @param effectiveId   effective id to check
     * @return              true iif the effective id has a suffix
     */
    public static boolean hasEffectiveIdSuffix(String effectiveId) {
        return (effectiveId != null) && effectiveId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1) != -1;
    }

    /**
     * Return the suffix of an effective id, e.g. "" or "2-5-1". The suffix returned does not start with a separator.
     *
     * @param effectiveId   effective id to check
     * @return              suffix if any, "" if none, null if effectiveId was null
     */
    public static String getEffectiveIdSuffix(String effectiveId) {
        if (effectiveId == null)
            return null;

        final int suffixIndex = effectiveId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1);
        if (suffixIndex != -1) {
            return effectiveId.substring(suffixIndex + 1);
        } else {
            return "";
        }
    }

    /**
     * Return the suffix of an effective id, e.g. "" or "2-5-1". The suffix returned starts with a separator.
     *
     * @param effectiveId   effective id to check
     * @return              suffix if any, "" if none, null if effectiveId was null
     */
    public static String getEffectiveIdSuffixWithSeparator(String effectiveId) {
        if (effectiveId == null)
            return null;

        final int suffixIndex = effectiveId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1);
        if (suffixIndex != -1) {
            return effectiveId.substring(suffixIndex);
        } else {
            return "";
        }
    }

    /**
     * Return an effective id's prefixed id, i.e. the effective id without its suffix, e.g.:
     *
     * o foo$bar$my-input.1-2 => foo$bar$my-input
     *
     * @param effectiveId   effective id to check
     * @return              effective id without its suffix, null if effectiveId was null
     */
    public static String getPrefixedId(String effectiveId) {
        if (effectiveId == null)
            return null;

        final int suffixIndex = effectiveId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1);
        if (suffixIndex != -1) {
            return effectiveId.substring(0, suffixIndex);
        } else {
            return effectiveId;
        }
    }

    /**
     * Return an effective id without its prefix, e.g.:
     *
     * o foo$bar$my-input => my-input
     * o foo$bar$my-input.1-2 => my-input.1-2
     *
     * @param effectiveId   effective id to check
     * @return              effective id without its prefix, null if effectiveId was null
     */
    public static String getEffectiveIdNoPrefix(String effectiveId) {
        if (effectiveId == null)
            return null;

        final int prefixIndex = effectiveId.lastIndexOf(XFormsConstants.COMPONENT_SEPARATOR);
        if (prefixIndex != -1) {
            return effectiveId.substring(prefixIndex + 1);
        } else {
            return effectiveId;
        }
    }

    /**
     * Return the parts of an effective id prefix, e.g. for foo$bar$my-input return new String[] { "foo", "bar" }
     *
     * @param effectiveId   effective id to check
     * @return              array of parts, empty array if no parts, null if effectiveId was null
     */
    public static String[] getEffectiveIdPrefixParts(String effectiveId) {
        if (effectiveId == null)
            return null;

        final int prefixIndex = effectiveId.lastIndexOf(XFormsConstants.COMPONENT_SEPARATOR);
        if (prefixIndex != -1) {
            return StringUtils.split(effectiveId.substring(0, prefixIndex), XFormsConstants.COMPONENT_SEPARATOR);
        } else {
            return new String[0];
        }
    }

    /**
     * Given a repeat control's effective id, compute the effective id of an iteration.
     *
     * @param repeatEffectiveId     repeat control effective id
     * @param iterationIndex        repeat iteration
     * @return                      repeat iteration effective id
     */
    public static String getIterationEffectiveId(String repeatEffectiveId, int iterationIndex) {
        final String parentSuffix = XFormsUtils.getEffectiveIdSuffix(repeatEffectiveId);
        if (parentSuffix.equals("")) {
            // E.g. foobar => foobar.3
            return repeatEffectiveId + XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1 + iterationIndex;
        } else {
            // E.g. foobar.3-7 => foobar.3-7-2
            return repeatEffectiveId + XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_2 + iterationIndex;
        }
    }

    /**
     * Return the parts of an effective id suffix, e.g. for $foo$bar.3-1-5 return new Integer[] { 3, 1, 5 }
     *
     * @param effectiveId   effective id to check
     * @return              array of parts, empty array if no parts, null if effectiveId was null
     */
    public static Integer[] getEffectiveIdSuffixParts(String effectiveId) {
        if (effectiveId == null)
            return null;

        final int suffixIndex = effectiveId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1);
        if (suffixIndex != -1) {
            final String[] stringResult = StringUtils.split(effectiveId.substring(suffixIndex + 1), XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_2);
            final Integer[] result = new Integer[stringResult.length];
            for (int i = 0; i < stringResult.length; i++) {
                final String currentString = stringResult[i];
                result[i] = new Integer(currentString);
            }
            return result;
        } else {
            return new Integer[0];
        }
    }

    /**
     * Compute an effective id based on an existing effective id and a static id. E.g.:
     *
     *  foo$bar.1-2 and myStaticId => foo$myStaticId.1-2
     *
     * @param baseEffectiveId   base effective id
     * @param staticId          static id
     * @return                  effective id
     */
    public static String getRelatedEffectiveId(String baseEffectiveId, String staticId) {
        final String prefix = getEffectiveIdPrefix(baseEffectiveId);
        final String suffix; {
            final int suffixIndex = baseEffectiveId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1);
            suffix = (suffixIndex == -1) ? "" : baseEffectiveId.substring(suffixIndex);
        }
        return prefix + staticId + suffix;
    }

    /**
     * Return the static id associated with the given id, removing suffix and prefix if present.
     *
     *  foo$bar.1-2 => bar
     *
     * @param anyId id to check
     * @return      static id, or null if anyId was null
     */
    public static String getStaticIdFromId(String anyId) {
        return getPrefixedId(getEffectiveIdNoPrefix(anyId));
    }

    /**
     * Append a new string to an effective id.
     *
     *   foo$bar.1-2 and -my-ending => foo$bar-my-ending.1-2
     *
     * @param effectiveId   base effective id
     * @param ending        new ending
     * @return              effective id
     */
    public static String appendToEffectiveId(String effectiveId, String ending) {
        final String prefixedId = getPrefixedId(effectiveId);
        return prefixedId + ending + getEffectiveIdSuffixWithSeparator(effectiveId);
    }

    /**
     * Check if an id is a static id, i.e. if it does not contain component/hierarchy separators.
     *
     * @param staticId  static id to check
     * @return          true if the id is a static id
     */
    public static boolean isStaticId(String staticId) {
        return staticId.indexOf(XFormsConstants.COMPONENT_SEPARATOR) == -1 && staticId.indexOf(XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1) == -1;
    }

    /**
     * Check if an item is an element node.
     *
     * @param item  item to check
     * @return      true iif the item is an element node
     */
    public static boolean isElement(Item item) {
        return (item instanceof NodeInfo) && ((NodeInfo) item).getNodeKind() == org.w3c.dom.Document.ELEMENT_NODE;
    }

    /**
     * Check if an item is an document node.
     *
     * @param item  item to check
     * @return      true iif the item is an document node
     */
    public static boolean isDocument(Item item) {
        return (item instanceof NodeInfo) && ((NodeInfo) item).getNodeKind() == org.w3c.dom.Document.DOCUMENT_NODE;
    }

    /**
     * Return the value of a bound item, whether a NodeInfo or an AtomicValue. If none of those, return null;
     *
     * @param boundItem item to get value
     * @return          value or null
     */
    public static String getBoundItemValue(Item boundItem) {
        if (boundItem instanceof NodeInfo && XFormsUtils.isDocument(boundItem)) {
            // As a special case, we sometimes allow binding to a document node, but consider the value is empty in this case
            return null;
        } else if (boundItem instanceof NodeInfo) {
            // Bound to element or attribute
            return XFormsInstance.getValueForNodeInfo((NodeInfo) boundItem);
        } else if (boundItem instanceof AtomicValue) {
            // Bound to an atomic value
            return ((AtomicValue) boundItem).getStringValue();
        } else {
            return null;
        }
    }

    /**
     * Return the id of the enclosing HTML <form> element.
     *
     * @param containingDocument    containing document
     * @return                      id, possibly namespaced
     */
    public static String getFormId(XFormsContainingDocument containingDocument) {
        return namespaceId(containingDocument, "xforms-form");
    }

    public static boolean compareItems(Item item1, Item item2) {
        if (item1 == null && item2 == null) {
            return true;
        }
        if (item1 == null || item2 == null) {
            return false;
        }

        if (item1 instanceof StringValue) {
            // Saxon doesn't allow equals() on StringValue because it requires a collation and equals() might throw
            if (item2 instanceof StringValue) {
                final StringValue currentStringValue = (StringValue) item1;
                if (currentStringValue.codepointEquals((StringValue) item2)) {
                    return true;
                }
            }
        } else if (item1 instanceof NumericValue) {
            // Saxon doesn't allow equals() between numeric and non-numeric values
            if (item2 instanceof NumericValue) {
                final NumericValue currentNumericValue = (NumericValue) item1;
                if (currentNumericValue.equals((NumericValue) item2)) {
                    return true;
                }
            }
        } else if (item1 instanceof AtomicValue) {
            if (item2 instanceof AtomicValue) {
                final AtomicValue currentAtomicValue = (AtomicValue) item1;
                if (currentAtomicValue.equals((AtomicValue) item2)) {
                    return true;
                }
            }
        } else {
            if (item1.equals(item2)) {// equals() is the same as isSameNodeInfo() for NodeInfo, and compares the values for values
                return true;
            }
        }
        return false;
    }

    /**
     * Get an element's static id.
     *
     * @param element   element to check
     * @return          static id or null
     */
    public static String getElementStaticId(Element element) {
        return element.attributeValue(XFormsConstants.ID_QNAME);
    }
}
