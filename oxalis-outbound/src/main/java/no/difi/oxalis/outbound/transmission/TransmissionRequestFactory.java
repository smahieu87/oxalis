/*
 * Copyright 2010-2018 Norwegian Agency for Public Management and eGovernment (Difi)
 *
 * Licensed under the EUPL, Version 1.1 or – as soon they
 * will be approved by the European Commission - subsequent
 * versions of the EUPL (the "Licence");
 *
 * You may not use this work except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/community/eupl/og_page/eupl
 *
 * Unless required by applicable law or agreed to in
 * writing, software distributed under the Licence is
 * distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the Licence for the specific language governing
 * permissions and limitations under the Licence.
 */

package no.difi.oxalis.outbound.transmission;

import brave.Span;
import brave.Tracer;
import no.difi.oxalis.api.lang.OxalisContentException;
import no.difi.oxalis.api.outbound.TransmissionMessage;
import no.difi.oxalis.api.transformer.ContentDetector;
import no.difi.oxalis.api.transformer.ContentWrapper;
import no.difi.oxalis.commons.io.PeekingInputStream;
import no.difi.oxalis.commons.tracing.Traceable;
import no.difi.vefa.peppol.common.model.Header;
import no.difi.vefa.peppol.sbdh.SbdReader;
import no.difi.vefa.peppol.sbdh.lang.SbdhException;

import javax.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * @author erlend
 * @since 4.0.0
 */
public class TransmissionRequestFactory extends Traceable {

    private final ContentDetector contentDetector;

    private final ContentWrapper contentWrapper;

    @Inject
    public TransmissionRequestFactory(ContentDetector contentDetector, ContentWrapper contentWrapper, Tracer tracer) {
        super(tracer);
        this.contentDetector = contentDetector;
        this.contentWrapper = contentWrapper;
    }

    public TransmissionMessage newInstance(InputStream inputStream)
            throws IOException, OxalisContentException {
        Span root = tracer.newTrace().name(getClass().getSimpleName()).start();
        try {
            return perform(inputStream, root);
        } finally {
            root.finish();
        }
    }

    public TransmissionMessage newInstance(InputStream inputStream, Span root)
            throws IOException, OxalisContentException {
        Span span = tracer.newChild(root.context()).name(getClass().getSimpleName()).start();
        try {
            return perform(inputStream, span);
        } finally {
            span.finish();
        }
    }

    private TransmissionMessage perform(InputStream inputStream, Span root)
            throws IOException, OxalisContentException {
        PeekingInputStream peekingInputStream = new PeekingInputStream(inputStream);

        // Read header from content to send.
        Header header;
        try {
            // Read header from SBDH.
            Span span = tracer.newChild(root.context()).name("Reading SBDH").start();
            try (SbdReader sbdReader = SbdReader.newInstance(peekingInputStream)) {
                header = sbdReader.getHeader();
                span.tag("identifier", header.getIdentifier().getIdentifier());
            } catch (SbdhException e) {
                span.tag("exception", e.getMessage());
                throw e;
            } finally {
                span.finish();
            }

            // Create transmission request.
            return new DefaultTransmissionMessage(header, peekingInputStream.newInputStream());
        } catch (SbdhException e) {
            byte[] payload = peekingInputStream.getContent();

            // Detect header from content.
            Span span = tracer.newChild(root.context()).name("Detect SBDH from content").start();
            try {
                header = contentDetector.parse(new ByteArrayInputStream(payload));
                span.tag("identifier", header.getIdentifier().getIdentifier());
            } catch (OxalisContentException ex) {
                span.tag("exception", ex.getMessage());
                throw new OxalisContentException(ex.getMessage(), ex);
            } finally {
                span.finish();
            }

            // Wrap content in SBDH.
            span = tracer.newChild(root.context()).name("Wrap content in SBDH").start();
            InputStream wrappedContent;
            try {
                wrappedContent = contentWrapper.wrap(new ByteArrayInputStream(payload), header);
            } catch (OxalisContentException ex) {
                span.tag("exception", ex.getMessage());
                throw ex;
            } finally {
                span.finish();
            }

            // Create transmission request.
            return new DefaultTransmissionMessage(header, wrappedContent);
        }
    }
}