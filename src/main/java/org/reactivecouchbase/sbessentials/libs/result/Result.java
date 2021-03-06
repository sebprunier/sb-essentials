package org.reactivecouchbase.sbessentials.libs.result;

import akka.stream.OverflowStrategy;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.StreamConverters;
import akka.util.ByteString;
import com.google.common.io.Files;
import javaslang.collection.HashMap;
import javaslang.collection.HashSet;
import javaslang.collection.List;
import javaslang.collection.Map;
import org.reactivecouchbase.common.Throwables;
import org.reactivecouchbase.concurrent.Future;
import org.reactivecouchbase.concurrent.Promise;
import org.reactivecouchbase.json.JsValue;
import org.reactivecouchbase.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

import javax.servlet.http.Cookie;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

public class Result {

    private static final Logger logger = LoggerFactory.getLogger(Result.class);

    public final int status;
    public final Source<ByteString, ?> source;
    public final HashMap<String, List<String>> headers;
    public final HashSet<Cookie> cookies;
    public final String contentType;
    public final Promise<Object> materializedValue = Promise.create();

    public Result(int status, Source<ByteString, ?> source, String contentType, HashMap<String, List<String>> headers, HashSet<Cookie> cookies) {
        this.status = status;
        this.source = source;
        this.headers = headers;
        this.cookies = cookies;
        this.contentType = contentType;
    }

    public Result(int status) {
        this(status, Source.empty(), "text/plain", HashMap.<String, List<String>>empty(), HashSet.<Cookie>empty());
    }

    public Result(int status, Source<ByteString, ?> source) {
        this(status, source, "text/plain", HashMap.<String, List<String>>empty(), HashSet.<Cookie>empty());
    }

    public Result(int status, String contentType) {
        this(status, Source.empty(), contentType, HashMap.<String, List<String>>empty(), HashSet.<Cookie>empty());
    }

    private Result(Builder builder) {
        status = builder.status;
        source = builder.source;
        headers = builder.headers;
        cookies = builder.cookies;
        contentType = builder.contentType;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static Builder copy(Result copy) {
        Builder builder = new Builder();
        builder.status = copy.status;
        builder.source = copy.source;
        builder.headers = copy.headers;
        builder.cookies = copy.cookies;
        builder.contentType = copy.contentType;
        return builder;
    }

    public Result as(String contentType) {
        return Result.copy(this).withContentType(contentType).build();
    }

    public Result as(MediaType mediaType) {
        return Result.copy(this).withContentType(mediaType.toString()).build();
    }

    public Result withHeader(String key, String value) {
        if (!headers.containsKey(key)) {
            return Result.copy(this)
                    .withHeaders(headers.put(key, List.of(value)))
                    .build();
        }
        return Result
                .copy(this)
                .withHeaders(headers.put(key, headers.get(key).get().append(value)))
                .build();
    }

    public Result withStatus(int status) {
        return Result.copy(this).withStatus(status).build();
    }

    public Result withCookie(Cookie cookie) {
        return Result.copy(this).withCookies(this.cookies.add(cookie)).build();
    }

    public Result removeCookie(Cookie cookie) {
        throw new RuntimeException("Not supported yet !");
    }

    public Result withBody(Source<ByteString, ?> source) {
        return Result.copy(this).withSource(source).build();
    }

    public Result text(String text) {
        Source<ByteString, ?> source = StreamConverters.fromInputStream(() -> new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        return Result.copy(this)
                .withSource(source)
                .withContentType(MediaType.TEXT_PLAIN_VALUE)
                .build();
    }

    public Result xml(String xml) {
        Source<ByteString, ?> source = StreamConverters.fromInputStream(() -> new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        return Result.copy(this)
                .withSource(source)
                .withContentType(MediaType.APPLICATION_XML_VALUE)
                .build();
    }
    public Result xml(org.w3c.dom.Node xml) {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            StreamResult result = new StreamResult(new StringWriter());
            DOMSource source = new DOMSource(xml);
            transformer.transform(source, result);
            String xmlString = result.getWriter().toString();
            return text(xmlString).as(MediaType.APPLICATION_XML_VALUE);
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    public Result json(String json) {
        Source<ByteString, ?> source = StreamConverters.fromInputStream(() -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        return Result.copy(this)
                .withSource(source)
                .withContentType(MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public Result json(JsValue json) {
        Source<ByteString, ?> source = StreamConverters.fromInputStream(() -> new ByteArrayInputStream(Json.stringify(json).getBytes(StandardCharsets.UTF_8)));
        return Result.copy(this)
                .withSource(source)
                .withContentType(MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public Result sendFile(File file) {
        Source<ByteString, ?> source = StreamConverters.fromInputStream(() -> Files.asByteSource(file).openStream());
        return Result.copy(this).withSource(source).build();
    }

    public Result sendPath(Path path) {
        return sendFile(path.toFile());
    }

    public Result binary(InputStream is) {
        Source<ByteString, ?> source = StreamConverters.fromInputStream(() -> is);
        return Result.copy(this)
                .withSource(source)
                .withContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .build();
    }

    public Result binary(byte[] bytes) {
        Source<ByteString, ?> source = Source
                .from(Arrays.asList(bytes))
                .buffer(8192, OverflowStrategy.backpressure())
                .map(ByteString::fromArray);
        return Result.copy(this)
                .withSource(source)
                .withContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .build();
    }

    public Result binary(Source<ByteString, ?> bytes) {
        return Result.copy(this)
                .withSource(bytes)
                .withContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .build();
    }

    public Result html(String html) {
        Source<ByteString, ?> source = StreamConverters.fromInputStream(() -> new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
        return Result.copy(this)
                .withSource(source)
                .withContentType(MediaType.TEXT_HTML_VALUE)
                .build();
    }

    public Result template(String name, Map<String, Object> params) {
        org.thymeleaf.context.Context context = new org.thymeleaf.context.Context();
        org.thymeleaf.TemplateEngine templateEngine = Results.webApplicationContext.getBean(org.thymeleaf.TemplateEngine.class);
        params.forEach(tuple -> context.setVariable(tuple._1, tuple._2));
        String body = templateEngine.process(name, context);
        return html(body);
    }

    public Result chunked(Source<ByteString, ?> theStream) {
        return Result.copy(this).withSource(theStream).build();
    }

    public Result stream(Source<String, ?> stream) {
        return Result.copy(this).withSource(stream.map(ByteString::fromString)).build();
    }

    public Future<Object> materializedValue() {
        return materializedValue.future();
    }

    public <T> Future<T> materializedValue(Class<T> as) {
        return materializedValue.future().mapTo(as);
    }

    public String toString() {
        return "Result { "
                + status
                + ", "
                + contentType
                + ", [ "
                + headers.mkString(", ")
                + " ], "
                + source
                + " }";
    }

    public static final class Builder {
        private int status;
        private Source<ByteString, ?> source;
        private HashMap<String, List<String>> headers;
        private HashSet<Cookie> cookies;
        private String contentType;

        private Builder() {
        }

        public Builder withStatus(int val) {
            status = val;
            return this;
        }

        public Builder withSource(Source<ByteString, ?> val) {
            source = val;
            return this;
        }

        public Builder withHeaders(HashMap<String, List<String>> val) {
            headers = val;
            return this;
        }

        public Builder withCookies(HashSet<Cookie> val) {
            cookies = val;
            return this;
        }

        public Builder withContentType(String val) {
            contentType = val;
            return this;
        }

        public Result build() {
            return new Result(this);
        }
    }
}
