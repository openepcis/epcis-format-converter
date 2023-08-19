/*
 * Copyright 2022-2023 benelog GmbH & Co. KG
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 */
package io.openepcis.convert;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openepcis.constants.EPCIS;
import io.openepcis.constants.EPCISFormat;
import io.openepcis.constants.EPCISVersion;
import io.openepcis.convert.collector.EventHandler;
import io.openepcis.convert.collector.JsonEPCISEventCollector;
import io.openepcis.convert.collector.XmlEPCISEventCollector;
import io.openepcis.convert.exception.FormatConverterException;
import io.openepcis.convert.json.JSONEventValueTransformer;
import io.openepcis.convert.json.JsonToXmlConverter;
import io.openepcis.convert.util.ChannelUtil;
import io.openepcis.convert.xml.ProblemResponseBodyMarshaller;
import io.openepcis.convert.xml.XMLEventValueTransformer;
import io.openepcis.convert.xml.XmlToJsonConverter;
import io.openepcis.convert.xml.XmlVersionTransformer;
import io.openepcis.model.rest.ProblemResponseBody;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

@Slf4j
public class VersionTransformer {

    private final ObjectMapper objectMapper =
            new ObjectMapper()
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .setSerializationInclusion(JsonInclude.Include.NON_EMPTY);

    private final ExecutorService executorService;
    private final XmlVersionTransformer xmlVersionTransformer;
    private final XmlToJsonConverter xmlToJsonConverter;
    private final JsonToXmlConverter jsonToXmlConverter;
    private final JSONEventValueTransformer jsonEventValueTransformer;

    private final XMLEventValueTransformer xmlEventValueTransformer;

    private Optional<Function<Object, Object>> epcisEventMapper = Optional.empty();

    public VersionTransformer(final ExecutorService executorService, final JAXBContext jaxbContext) {
        this.executorService = executorService;
        this.xmlVersionTransformer = XmlVersionTransformer.newInstance(this.executorService);
        this.xmlToJsonConverter = new XmlToJsonConverter(jaxbContext);
        this.jsonToXmlConverter = new JsonToXmlConverter(jaxbContext);
        this.jsonEventValueTransformer = new JSONEventValueTransformer();
        this.xmlEventValueTransformer = new XMLEventValueTransformer(jaxbContext);
    }

    private VersionTransformer(VersionTransformer parent, Function<Object, Object> eventMapper) {
        this.executorService = parent.executorService;
        this.xmlVersionTransformer = parent.xmlVersionTransformer;
        this.jsonToXmlConverter = parent.jsonToXmlConverter.mapWith(eventMapper);
        this.xmlToJsonConverter = parent.xmlToJsonConverter.mapWith(eventMapper);
        this.jsonEventValueTransformer = parent.jsonEventValueTransformer.mapWith(eventMapper);
        this.xmlEventValueTransformer = parent.xmlEventValueTransformer.mapWith(eventMapper);
        this.epcisEventMapper = Optional.ofNullable(eventMapper);
    }

    public VersionTransformer(final ExecutorService executorService) throws JAXBException {
        this.executorService = executorService;
        this.xmlVersionTransformer = XmlVersionTransformer.newInstance(this.executorService);
        this.xmlToJsonConverter = new XmlToJsonConverter();
        this.jsonToXmlConverter = new JsonToXmlConverter();
        this.jsonEventValueTransformer = new JSONEventValueTransformer();
        this.xmlEventValueTransformer = new XMLEventValueTransformer();
    }

    public VersionTransformer() throws JAXBException {
        this(Executors.newWorkStealingPool());
    }

    /**
     * Method with autodetect EPCIS version from inputStream
     *
     * @param inputDocument EPCIS document in either application/xml or application/json format as a
     *                      InputStream
     * @param conversion    Conversion object with required fields.
     * @return returns the converted EPCIS document as InputStream which can be used for further
     * processing
     * @throws UnsupportedOperationException if user is trying to convert different version other than
     *                                       specified then throw the error
     */
    public final InputStream convert(
            final InputStream inputDocument,
            final Conversion conversion)
            throws UnsupportedOperationException, IOException {

        // Checking if mediaType is JSON_LD, and detecting version conditionally
        Map<String, Object> result = EPCISFormat.JSON_LD.equals(conversion.fromMediaType()) ? null : versionDetector(inputDocument);
        EPCISVersion fromVersion = result == null ? EPCISVersion.VERSION_2_0_0 : (EPCISVersion) result.get("version");

        InputStream inputStream = inputDocument;
        // If version detected, result won't be null, thus do InputStream operations
        if (result != null) {
            final byte[] preScan = (byte[]) result.get("preScan");
            final int len = (int) result.get("len");

            final PipedOutputStream pipedOutputStream = new PipedOutputStream();
            final PipedInputStream pipe = new PipedInputStream(pipedOutputStream);
            pipedOutputStream.write(preScan, 0, len);

            executorService.execute(() -> {
                try {
                    ChannelUtil.copy(inputDocument, pipedOutputStream);
                } catch (Exception e) {
                    throw new FormatConverterException(
                            "Exception occurred during reading of schema version from input document : "
                                    + e.getMessage(),
                            e);
                }
            });
            inputStream = pipe;
        }


        final Conversion conversionToPerform = Conversion.of(
                conversion.fromMediaType(),
                fromVersion,
                conversion.toMediaType(),
                conversion.toVersion(),
                conversion.generateGS1CompliantDocument()
        );

        return performConversion(inputStream, conversionToPerform);

    }

    /**
     * Method with autodetect EPCIS version from inputStream
     *
     * @param epcisDocument EPCIS document in application/xml or application/json format as a InputStream
     * @return returns the detected version with read prescan details for merging back again.
     * @throws IOException if unable to read the document
     */
    public final Map<String, Object> versionDetector(final InputStream epcisDocument)
            throws IOException {
        // pre scan 1024 bytes to detect version
        final byte[] preScan = new byte[1024];
        final int len = epcisDocument.read(preScan);
        final String preScanVersion = new String(preScan, StandardCharsets.UTF_8);

        if (!preScanVersion.contains(EPCIS.SCHEMA_VERSION)) {
            throw new FormatConverterException(
                    "Unable to detect EPCIS schemaVersion for given document, please check the document again");
        }

        EPCISVersion fromVersion;

        if (preScanVersion.contains(EPCIS.SCHEMA_VERSION + "=\"1.2\"")
                || preScanVersion.contains(EPCIS.SCHEMA_VERSION + "='1.2'")
                || preScanVersion.replace(" ", "").contains("\"" + EPCIS.SCHEMA_VERSION + "\":\"1.2\"")) {
            fromVersion = EPCISVersion.VERSION_1_2_0;
        } else if (preScanVersion.contains(EPCIS.SCHEMA_VERSION + "=\"2.0\"")
                || preScanVersion.contains(EPCIS.SCHEMA_VERSION + "='2.0'")
                || preScanVersion.replace(" ", "").contains("\"" + EPCIS.SCHEMA_VERSION + "\":\"2.0\"")) {
            fromVersion = EPCISVersion.VERSION_2_0_0;
        } else {
            throw new FormatConverterException(
                    "Provided document contains unsupported EPCIS document version");
        }

        final Map<String, Object> result = new HashMap<>();

        result.put("version", fromVersion);
        result.put("preScan", preScan);
        result.put("len", len);

        return result;
    }

    /**
     * API method to accept EPCIS document input and transform it to corresponding document based on
     * user specification.
     *
     * @param inputDocument EPCIS document in either application/xml or application/json format as a
     *                      InputStream
     * @return returns the converted document as InputStream which can be used for further processing
     * @throws UnsupportedOperationException if user is trying to convert different version other than
     *                                       specified then throw the error
     * @throws IOException                   If any exception occur during the conversion then throw the error
     */
    public final InputStream performConversion(
            final InputStream inputDocument,
            final Conversion conversion)
            throws UnsupportedOperationException, IOException {
        // If input fromVersion and the required output toVersion is same then return the same input.
        if (EPCISFormat.XML.equals(conversion.fromMediaType()) && EPCISFormat.XML.equals(conversion.toMediaType())) {

            if (conversion.toVersion().equals(EPCISVersion.VERSION_1_2_0)) {
                InputStream streamWithPreferences = conversion.fromVersion().equals(EPCISVersion.VERSION_2_0_0) ? fromXmlToXml(inputDocument) : fromXmlToXml(xmlVersionTransformer.xmlConverter(inputDocument, EPCISVersion.VERSION_1_2_0, EPCISVersion.VERSION_2_0_0, conversion.generateGS1CompliantDocument()));
                return xmlVersionTransformer.xmlConverter(streamWithPreferences, EPCISVersion.VERSION_2_0_0, conversion.toVersion(), conversion.generateGS1CompliantDocument());
            } else {
                return conversion.fromVersion().equals(EPCISVersion.VERSION_2_0_0) ? fromXmlToXml(inputDocument) : fromXmlToXml(xmlVersionTransformer.xmlConverter(inputDocument, EPCISVersion.VERSION_1_2_0, EPCISVersion.VERSION_2_0_0, conversion.generateGS1CompliantDocument()));
            }
        } else if (EPCISFormat.JSON_LD.equals(conversion.fromMediaType())
                && EPCISFormat.XML.equals(conversion.toMediaType())
                && EPCISVersion.VERSION_2_0_0.equals(conversion.fromVersion())
                && EPCISVersion.VERSION_2_0_0.equals(conversion.toVersion())) {
            // If fromMedia is json and toMedia is xml and both versions are 2.0
            return toXml(inputDocument);
        } else if (EPCISFormat.JSON_LD.equals(conversion.fromMediaType())
                && EPCISFormat.XML.equals(conversion.toMediaType())
                && EPCISVersion.VERSION_2_0_0.equals(conversion.fromVersion())
                && EPCISVersion.VERSION_1_2_0.equals(conversion.toVersion())) {
            // If fromMedia is json and toMedia is xml and fromVersion is 2.0 and toVersion is 1.2
            return xmlVersionTransformer.xmlConverter(toXml(inputDocument), EPCISVersion.VERSION_2_0_0, EPCISVersion.VERSION_1_2_0, conversion.generateGS1CompliantDocument());
        } else if (EPCISFormat.XML.equals(conversion.fromMediaType())
                && EPCISFormat.JSON_LD.equals(conversion.toMediaType())
                && EPCISVersion.VERSION_2_0_0.equals(conversion.fromVersion())
                && EPCISVersion.VERSION_2_0_0.equals(conversion.toVersion())) {
            // If fromMedia is xml and toMedia is json and both versions are 2.0 convert xml->json
            return toJson(inputDocument);
        } else if (EPCISFormat.XML.equals(conversion.fromMediaType())
                && EPCISFormat.JSON_LD.equals(conversion.toMediaType())
                && EPCISVersion.VERSION_1_2_0.equals(conversion.fromVersion())
                && EPCISVersion.VERSION_2_0_0.equals(conversion.toVersion())) {
            // If fromMedia is xml and toMedia is json and fromVersion is 1.2, toVersion 2.0 then convert
            // xml->2.0 and then to JSON
            return toJson(xmlVersionTransformer.xmlConverter(inputDocument, EPCISVersion.VERSION_1_2_0, EPCISVersion.VERSION_2_0_0, conversion.generateGS1CompliantDocument()));
        } else if (EPCISFormat.JSON_LD.equals(conversion.fromMediaType())
                && EPCISFormat.JSON_LD.equals(conversion.toMediaType())
                && EPCISVersion.VERSION_2_0_0.equals(conversion.fromVersion())
                && EPCISVersion.VERSION_2_0_0.equals(conversion.toVersion())) {
            // If fromMedia is json and toMedia is xml and fromVersion is 2.0 and toVersion is 1.2
            return fromJsonToJson(inputDocument);
        } else {
            throw new UnsupportedOperationException(
                    "Requested conversion is not supported, Please check provided MediaType/Version and try again");
        }
    }

    // Private method to convert the JSON 2.0 document -> XML 2.0 and return it as InputStream
    private InputStream toXml(final InputStream inputDocument) {
        try {
            final PipedOutputStream xmlOutputStream = new PipedOutputStream();
            final EventHandler<? extends XmlEPCISEventCollector> handler =
                    new EventHandler(new XmlEPCISEventCollector(xmlOutputStream));

            final PipedInputStream convertedDocument = new PipedInputStream(xmlOutputStream);

            executorService.execute(
                    () -> {
                        try {
                            jsonToXmlConverter.convert(inputDocument, handler);
                            xmlOutputStream.close();
                        } catch (Exception e) {
                            try {
                                ProblemResponseBodyMarshaller.getMarshaller().marshal(ProblemResponseBody.fromException(e), xmlOutputStream);
                                xmlOutputStream.close();
                            } catch (IOException ioe) {
                                log.warn("Couldn't write or close the stream", ioe);
                            } catch (JAXBException ex) {
                                throw new RuntimeException(ex);
                            }
                        }
                    });

            return convertedDocument;
        } catch (Exception e) {
            throw new FormatConverterException(
                    "Exception occurred during the conversion of JSON 2.0 document to XML 2.0 document using PipedInputStream : "
                            + e.getMessage(),
                    e);
        }
    }

    // Private method to convert the XML 2.0 document -> JSON 2.0 document and return as InputStream
    private InputStream toJson(final InputStream inputDocument) {
        try {
            final PipedOutputStream jsonOutputStream = new PipedOutputStream();
            final EventHandler<? extends JsonEPCISEventCollector> handler =
                    new EventHandler(new JsonEPCISEventCollector(jsonOutputStream));

            final InputStream convertedDocument = new PipedInputStream(jsonOutputStream);

            executorService.execute(
                    () -> {
                        try {
                            xmlToJsonConverter.convert(inputDocument, handler);
                        } catch (Exception e) {
                            try {
                                jsonOutputStream.write(objectMapper.writeValueAsBytes(ProblemResponseBody.fromException(e)));
                                jsonOutputStream.close();
                            } catch (IOException ioe) {
                                log.warn("Couldn't write or close the stream", ioe);
                            }
                        }
                    });
            return convertedDocument;
        } catch (Exception e) {
            throw new FormatConverterException(
                    "Exception occurred during the conversion of XML 2.0 document to JSON 2.0 document using PipedInputStream  : "
                            + e.getMessage(),
                    e);
        }
    }

    // Private method to convert the JSON/JSON LD 2.0 document -> JSON 2.0 document and return as InputStream
    private InputStream fromJsonToJson(final InputStream inputDocument) {
        try {
            final PipedOutputStream jsonOutputStream = new PipedOutputStream();
            final EventHandler<? extends JsonEPCISEventCollector> handler =
                    new EventHandler(new JsonEPCISEventCollector(jsonOutputStream));

            final InputStream convertedDocument = new PipedInputStream(jsonOutputStream);

            executorService.execute(
                    () -> {
                        try {
                            jsonEventValueTransformer.convert(inputDocument, handler);
                        } catch (Exception e) {
                            try {
                                jsonOutputStream.write(objectMapper.writeValueAsBytes(ProblemResponseBody.fromException(e)));
                                jsonOutputStream.close();
                            } catch (IOException ioe) {
                                log.warn("Couldn't write or close the stream", ioe);
                            }
                        }
                    });
            return convertedDocument;
        } catch (Exception e) {
            throw new FormatConverterException(
                    "Exception occurred during the conversion of XML 2.0 document to JSON 2.0 document using PipedInputStream  : "
                            + e.getMessage(),
                    e);
        }
    }

    // Private method to convert the XML 2.0 document -> JSON 2.0 document and return as InputStream
    private InputStream fromXmlToXml(final InputStream inputDocument) {
        try {
            final PipedOutputStream xmlOutputStream = new PipedOutputStream();
            final EventHandler<? extends XmlEPCISEventCollector> handler =
                    new EventHandler(new XmlEPCISEventCollector(xmlOutputStream));

            final PipedInputStream convertedDocument = new PipedInputStream(xmlOutputStream);

            executorService.execute(
                    () -> {
                        try {
                            xmlEventValueTransformer.convert(inputDocument, handler);
                            xmlOutputStream.close();
                        } catch (Exception e) {
                            try {
                                ProblemResponseBodyMarshaller.getMarshaller().marshal(ProblemResponseBody.fromException(e), xmlOutputStream);
                                xmlOutputStream.close();
                            } catch (IOException ioe) {
                                log.warn("Couldn't write or close the stream", ioe);
                            } catch (JAXBException ex) {
                                throw new RuntimeException(ex);
                            }
                        }
                    });
            return convertedDocument;
        } catch (Exception e) {
            throw new FormatConverterException(
                    "Exception occurred during the conversion of JSON 2.0 document to XML 2.0 document using PipedInputStream : "
                            + e.getMessage(),
                    e);
        }
    }

    public final VersionTransformer mapWith(final Function<Object, Object> mapper) {
        return new VersionTransformer(this, mapper);
    }

    // For API backward compatibility
    @Deprecated(forRemoval = true)
    public final InputStream convert(
            final InputStream inputDocument,
            final EPCISFormat fromMediaType,
            final EPCISFormat toMediaType,
            final EPCISVersion toVersion,
            final boolean generateGS1CompliantDocument)
            throws UnsupportedOperationException, IOException {
        return convert(inputDocument,
                Conversion.builder()
                        .fromMediaType(fromMediaType)
                        .toMediaType(toMediaType)
                        .toVersion(toVersion)
                        .generateGS1CompliantDocument(generateGS1CompliantDocument)
                        .build());
    }

    @Deprecated(forRemoval = true)
    public final InputStream convert(
            final InputStream inputDocument,
            final EPCISFormat mediaType,
            final EPCISVersion fromVersion,
            final EPCISVersion toVersion,
            final boolean generateGS1CompliantDocument)
            throws UnsupportedOperationException, IOException {
        return convert(inputDocument,
                Conversion.builder()
                        .fromMediaType(mediaType)
                        .toVersion(toVersion)
                        .generateGS1CompliantDocument(generateGS1CompliantDocument)
                        .build());
    }

    @Deprecated(forRemoval = true)
    public final InputStream convert(
            final InputStream inputDocument,
            final EPCISFormat fromMediaType,
            final EPCISVersion fromVersion,
            final EPCISFormat toMediaType,
            final EPCISVersion toVersion,
            final boolean generateGS1CompliantDocument) throws UnsupportedOperationException, IOException {
        return convert(inputDocument,
                Conversion.of(fromMediaType, fromVersion, toMediaType, toVersion, generateGS1CompliantDocument));
    }

    @Deprecated(forRemoval = true)
    public final InputStream convert(
            final InputStream inputDocument,
            final EPCISFormat fromMediaType,
            final EPCISFormat toMediaType,
            final EPCISVersion toVersion)
            throws UnsupportedOperationException, IOException {
        return convert(inputDocument,
                Conversion.builder()
                        .fromMediaType(fromMediaType)
                        .toMediaType(toMediaType)
                        .toVersion(toVersion)
                        .build());
    }

    @Deprecated(forRemoval = true)
    public final InputStream convert(
            final InputStream inputDocument,
            final EPCISFormat mediaType,
            final EPCISVersion fromVersion,
            final EPCISVersion toVersion)
            throws UnsupportedOperationException, IOException {
        return convert(inputDocument,
                Conversion.builder()
                        .fromMediaType(mediaType)
                        .toVersion(toVersion)
                        .build());
    }

    @Deprecated(forRemoval = true)
    public final InputStream convert(
            final InputStream inputDocument,
            final EPCISFormat fromMediaType,
            final EPCISVersion fromVersion,
            final EPCISFormat toMediaType,
            final EPCISVersion toVersion) throws UnsupportedOperationException, IOException {
        return convert(inputDocument,
                Conversion.of(fromMediaType, fromVersion, toMediaType, toVersion));
    }
}
