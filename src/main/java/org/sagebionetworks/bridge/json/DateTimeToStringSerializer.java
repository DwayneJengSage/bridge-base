package org.sagebionetworks.bridge.json;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;

/** Custom serializer for Joda DateTime, because the one in jackson-datatype-joda serializes epoch milliseconds. */
public class DateTimeToStringSerializer extends JsonSerializer<DateTime> {
    /** {@inheritDoc} */
    @Override
    public void serialize(DateTime dateTime, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        jgen.writeString(dateTime.toString(ISODateTimeFormat.dateTime()));
    }
}
