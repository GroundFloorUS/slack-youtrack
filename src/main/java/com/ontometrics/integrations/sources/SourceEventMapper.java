
package com.ontometrics.integrations.sources;

import com.ontometrics.integrations.configuration.IssueTracker;
import com.ontometrics.integrations.events.Issue;
import com.ontometrics.integrations.events.ProcessEvent;
import com.ontometrics.integrations.events.ProcessEventChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by rob on 7/11/14.
 * Copyright (c) ontometrics 2014 All rights reserved
 */
public class SourceEventMapper {

    private final IssueTracker issueTracker;
    private Logger log = LoggerFactory.getLogger(SourceEventMapper.class);
    private XMLEventReader eventReader;
    private ProcessEvent lastEvent;


    public SourceEventMapper(IssueTracker issueTracker) {
        this.issueTracker = issueTracker;
    }

    /**
     * Once we have this open, we should make sure that we are not resending events we have already seen.
     *
     * @return the last event that was returned to the user of this class
     */
    public List<ProcessEvent> getLatestEvents() throws IOException {
        LinkedList<ProcessEvent> events = new LinkedList<>();
        try {
            InputStream is = issueTracker.getFeedUrl().openStream();
            XMLInputFactory inputFactory = XMLInputFactory.newInstance();
            eventReader = inputFactory.createXMLEventReader(is);
            DateFormat dateFormat = createEventDateFormat();
            while (eventReader.hasNext()) {
                XMLEvent nextEvent = eventReader.nextEvent();
                switch (nextEvent.getEventType()) {
                    case XMLStreamConstants.START_ELEMENT:
                        StartElement startElement = nextEvent.asStartElement();
                        String elementName = startElement.getName().getLocalPart();
                        if (elementName.equals("item")) {
                            ProcessEvent event = extractEventFromStream(dateFormat);

                            if (lastEvent != null && lastEvent.getKey().equals(event.getKey())) {
                                //we already processed this event before, stopping iteration
                                return events;
                            }
                            events.addFirst(event);
                        }
                }
            }
        } catch (XMLStreamException e) {
            throw new IOException("Failed to process XML", e);
        }
        return events;
    }

    /**
     * Since the primary interest is in what has been changed, we focus on getting changes
     * often and pushing them into the appropriate channels.
     *
     * @return changes made since we last checked
     */
    public List<ProcessEventChange> getLatestChanges() throws IOException {
        return getLatestEvents().stream()
                .flatMap(e -> getChanges(e).stream())
                .collect(Collectors.toList());
    }

    public List<ProcessEventChange> getChanges(ProcessEvent e) {
        return getChanges(e, null);
    }

    public List<ProcessEventChange> getChanges(ProcessEvent e, Date upToDate) {
        List<ProcessEventChange> changes = new ArrayList<>();
        try {
            InputStream inputStream = issueTracker.getChangesUrl(e.getIssue()).openStream();
            XMLInputFactory inputFactory = XMLInputFactory.newInstance();
            XMLEventReader reader = inputFactory.createXMLEventReader(inputStream);
            boolean processingChange = false;
            String fieldName = null;
            String oldValue = null, newValue = null;
            String updater = null;
            Date updated = null;
            while (reader.hasNext()){
                XMLEvent nextEvent = reader.nextEvent();
                switch (nextEvent.getEventType()){
                    case XMLStreamConstants.START_ELEMENT:
                        StartElement startElement = nextEvent.asStartElement();
                        String elementName = startElement.getName().getLocalPart();
                        if (elementName.equals("change")){
                            processingChange = true;
                        }
                        if (elementName.equals("field") && processingChange){
                            fieldName = startElement.getAttributeByName(new QName("", "name")).getValue();
                            boolean isChangeField = startElement.getAttributes().next().toString().contains("ChangeField");
                            if (isChangeField){
                                reader.nextEvent();
                                StartElement firstValueTag = reader.nextEvent().asStartElement();
                                if (firstValueTag.getName().getLocalPart().equals("oldValue")){
                                    oldValue = reader.getElementText();
                                    reader.nextEvent(); reader.nextEvent();
                                    newValue = reader.getElementText();
                                } else {
                                    newValue = reader.getElementText();
                                }
                            } else {
                                reader.nextEvent(); // eat value tag
                                reader.nextEvent();
                                String fieldValue = reader.getElementText();
                                if (fieldName.equals("updaterName")){
                                    updater = fieldValue;
                                } else if (fieldName.equals("updated")){
                                    updated = new Date(Long.parseLong(fieldValue));
                                }
                            }
                        }
                        break;
                    case XMLStreamConstants.END_ELEMENT:
                        EndElement endElement = nextEvent.asEndElement();
                        if (endElement.getName().getLocalPart().equals("change")){
                            ProcessEventChange change = new ProcessEventChange.Builder()
                                    .updater(updater)
                                    .updated(updated)
                                    .field(fieldName)
                                    .priorValue(oldValue)
                                    .currentValue(newValue)
                                    .build();
                            log.info("change: {}", change);
                            if (upToDate == null || change.getUpdated().after(upToDate)) {
                                changes.add(change);
                            }
                            processingChange = false;
                        }
                        break;

                }
            }
        } catch (IOException | XMLStreamException e1) {
            e1.printStackTrace();
        }
        return changes;
    }

    private DateFormat createEventDateFormat() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.ENGLISH);
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        return dateFormat;
    }

    /**
     * Given that the stream should automatically do this, this might not be needed.
     *
     * @return the last event returned the last time #getLatestEvents() was called.
     */
    public ProcessEvent getLastEvent() {
        return lastEvent;
    }

    public void setLastEvent(ProcessEvent lastEvent) {
        this.lastEvent = lastEvent;
    }

    private ProcessEvent extractEventFromStream(DateFormat dateFormat) {
        String prefix;
        int issueNumber;
        String currentTitle = "", currentLink = "", currentDescription = "";
        Date currentPublishDate = null;
        try {
            eventReader.nextEvent();
            StartElement titleTag = eventReader.nextEvent().asStartElement(); // start title tag
            if ("title".equals(titleTag.getName().getLocalPart())){
                currentTitle = eventReader.getElementText();
                eventReader.nextEvent(); // eat end tag
                eventReader.nextEvent();
                currentLink = eventReader.getElementText();
                eventReader.nextEvent(); eventReader.nextEvent();
                currentDescription = eventReader.getElementText().replace("\n", "").trim();
                eventReader.nextEvent(); eventReader.nextEvent();
                currentPublishDate = dateFormat.parse(getEventDate(eventReader.getElementText()));
            }
        } catch (XMLStreamException | ParseException e) {
            e.printStackTrace();
        }
        String t = currentTitle;
        prefix = t.substring(0, t.indexOf("-"));
        issueNumber = Integer.parseInt(t.substring(t.indexOf("-")+1, t.indexOf(":")));
        ProcessEvent event = new ProcessEvent.Builder()
                .issue(new Issue.Builder().id(issueNumber).projectPrefix(prefix).build())
                .title(currentTitle)
                .description(currentDescription)
                .link(currentLink)
                .published(currentPublishDate)
                .build();
        log.debug("process event extracted and built: {}", event);
        return event;
    }

    private String getEventDate(String date) {
        String UTC = "UT";
        if (date.contains("UT")) {
            return date.substring(0, date.indexOf(UTC));
        }
        return date;
    }

}
