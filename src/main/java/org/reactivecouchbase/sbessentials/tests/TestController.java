package org.reactivecouchbase.sbessentials.tests;

import akka.Done;
import akka.actor.ActorSystem;
import akka.actor.Cancellable;
import akka.http.javadsl.model.HttpRequest;
import akka.stream.javadsl.Source;
import org.reactivecouchbase.common.Duration;
import org.reactivecouchbase.concurrent.Future;
import org.reactivecouchbase.json.Json;
import org.reactivecouchbase.sbessentials.libs.actions.Action;
import org.reactivecouchbase.sbessentials.libs.actions.Actions;
import org.reactivecouchbase.sbessentials.libs.result.Result;
import org.reactivecouchbase.sbessentials.libs.ws.WS;
import org.reactivecouchbase.sbessentials.libs.ws.WSResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static akka.pattern.PatternsCS.after;
import static org.reactivecouchbase.sbessentials.libs.result.Results.BadRequest;
import static org.reactivecouchbase.sbessentials.libs.result.Results.Ok;

@RestController
@RequestMapping("/tests")
public class TestController {

    private final static Logger logger = LoggerFactory.getLogger(TestController.class);

    @Autowired ActorSystem actorSystem;

    private static Action ApiKeyCheck = (req, block) -> req.header("Api-Key").fold(
        () -> {
            logger.info("No API KEY provided");
            return Future.successful(BadRequest.json(Json.obj().with("error", "No API KEY provided")));
        },
        (value) -> {
            if (value.equalsIgnoreCase("12345")) {
                return block.apply(req);
            } else {
                logger.info("Bad API KEY provided {}", value);
                return Future.successful(BadRequest.json(Json.obj().with("error", "Bad API KEY")));
            }
        }
    );

    private static Action LogBefore = (req, block) -> {
        Long start = System.currentTimeMillis();
        logger.info("[Log] before action -> {}", req.getRequest().getRequestURI());
        return block.apply(req.setValue("start", start));
    };

    private static Action LogAfter = (req, block) -> block.apply(req).andThen(ttry -> {
        logger.info(
            "[Log] after action -> {} : took {}",
            req.getRequest().getRequestURI(),
            Duration.of(System.currentTimeMillis() - req.getValue("start", Long.class), TimeUnit.MILLISECONDS).toHumanReadable()
        );
    });

    private static Action Throttle(int limit, long perMillis) {
        AtomicLong next = new AtomicLong(System.currentTimeMillis());
        AtomicLong counter = new AtomicLong(0L);
        return (request, block) -> {
            if (System.currentTimeMillis() > next.get()) {
                next.set(System.currentTimeMillis() + perMillis);
                counter.set(0L);
            }
            if (counter.get() == limit) {
                logger.info("Too much call for {}", request.getRequest().getRequestURI());
                return Future.successful(BadRequest.json(Json.obj().with("error", "too much calls")));
            }
            counter.incrementAndGet();
            return block.apply(request);
        };
    }

    private static Action ApiManagedAction = LogBefore
                                                .andThen(ApiKeyCheck)
                                                .andThen(Throttle(2, 3000))
                                                .andThen(LogAfter);

    @RequestMapping(method = RequestMethod.GET, path = "/sse")
    public Future<Result> testStream() {
        return Actions.sync(ctx -> {

            Result result = Ok.stream(
                Source.tick(
                    FiniteDuration.apply(0, TimeUnit.MILLISECONDS),
                    FiniteDuration.apply(1, TimeUnit.SECONDS),
                    ""
                )
                .map(l -> Json.obj().with("time", System.currentTimeMillis()).with("value", l))
                .map(Json::stringify)
                .map(j -> "data: " + j)
                .map(j -> j + "\n\n")
            ).as("text/event-stream");

            result.materializedValue(Cancellable.class).andThen(ttry -> {
                for (Cancellable c : ttry.asSuccess()) {
                    after(
                        FiniteDuration.create(5, TimeUnit.SECONDS),
                        actorSystem.scheduler(),
                        actorSystem.dispatcher(),
                        CompletableFuture.completedFuture(Done.getInstance())
                    ).thenAccept(d ->
                        c.cancel()
                    );
                }
            });

            return result;
        });
    }

    @RequestMapping(method = RequestMethod.GET, path = "/text")
    public Future<Result> testText() {
        return Actions.sync(ctx ->
            Ok.text("Hello World!\n")
        );
    }

    @RequestMapping(method = RequestMethod.GET, path = "/huge")
    public Future<Result> testHugeText() {
        return ApiManagedAction.sync(ctx ->
            Ok.text(VERY_HUGE_TEXT + "\n")
        );
    }

    @RequestMapping(method = RequestMethod.GET, path = "/json")
    public Future<Result> testJson() {
        return ApiManagedAction.sync(ctx ->
            Ok.json(Json.obj().with("message", "Hello World!"))
        );
    }

    @RequestMapping(method = RequestMethod.GET, path = "/html")
    public Future<Result> testHtml() {
        return ApiManagedAction.sync(ctx ->
            Ok.html("<h1>Hello World!</h1>")
        );
    }

    @RequestMapping(method = RequestMethod.POST, path = "/post")
    public Future<Result> testPost() {
        return ApiManagedAction.sync(ctx ->
            Ok.chunked(ctx.bodyAsStream()).as("application/json")
        );
    }

    @RequestMapping(method = RequestMethod.GET, path = "/ws")
    public Future<Result> testWS() {
        return ApiManagedAction.async(ctx ->
            WS.call("http://freegeoip.net", HttpRequest.create("/json/"))
                .flatMap(WSResponse::body)
                .map(r -> r.json().pretty())
                .map(p -> Ok.json(p))
        );
    }

    private final static String HUGE_TEXT = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Vestibulum rhoncus ultrices neque, nec consectetur ex molestie et. Integer dolor purus, laoreet vel condimentum vel, pulvinar at augue. Quisque tempor ac nisl vitae faucibus. Nunc placerat lacus dolor, nec finibus nibh semper eget. Nullam ac ipsum egestas, porttitor leo eget, suscipit risus. Donec sit amet est at erat pellentesque condimentum eu quis mauris. Aliquam tristique consectetur neque, a euismod magna mattis in. Nullam ac orci lectus. Interdum et malesuada fames ac ante ipsum primis in faucibus. Curabitur iaculis, mauris non tempus sagittis, eros nisl maximus quam, sed euismod sapien est id nisl. Nulla vitae enim dictum, tincidunt lorem nec, posuere arcu. Nulla tempus elit eu magna euismod maximus. Morbi varius nulla velit, eget pulvinar augue gravida eu.\n" +
        "Curabitur enim nisl, sollicitudin at odio laoreet, finibus gravida tellus. Nulla auctor urna magna, non egestas eros dignissim sollicitudin. Vestibulum ante ipsum primis in faucibus orci luctus et ultrices posuere cubilia Curae; Nullam eget magna sit amet magna venenatis consequat vel vel lectus. Morbi fringilla pulvinar diam sed fermentum. Praesent ac tincidunt urna. Praesent in mi dolor. Curabitur posuere massa quis lectus fringilla, at congue ante faucibus. Mauris massa lacus, egestas quis consequat ac, pretium quis arcu. Fusce placerat vel massa eu blandit.\n" +
        "Curabitur fermentum, ante a tristique interdum, enim diam pulvinar urna, nec aliquet tellus lectus id lectus. Integer ullamcorper lacinia est vulputate pretium. In a dictum velit. In mattis justo sollicitudin iaculis iaculis. Quisque suscipit lorem vel felis accumsan, quis lobortis diam imperdiet. Nullam ornare metus massa, rutrum ullamcorper metus scelerisque a. Nullam finibus diam magna, et fringilla dui faucibus vel. Etiam semper libero sit amet ullamcorper consectetur. Curabitur velit ipsum, cursus sit amet justo eget, rhoncus congue enim. In elit ex, sodales vel odio non, ultricies egestas risus. Proin venenatis consectetur augue, et vestibulum leo dictum vel. Etiam id risus vitae dolor viverra blandit ut ac ante.\n" +
        "Quisque a nibh sem. Nulla facilisi. Ut gravida, dui et malesuada interdum, nunc arcu eleifend ligula, quis ornare tortor quam at ante. Vestibulum ac porta nibh, vitae imperdiet erat. Pellentesque nec lacus ex. Nullam sed hendrerit lacus. Curabitur varius sem sit amet tortor sollicitudin auctor. Donec eu feugiat enim, quis pellentesque urna. Morbi finibus fermentum varius. Aliquam quis efficitur nisi. Cras at tortor erat. Vestibulum interdum diam lacus, a lacinia mauris dapibus ut. Suspendisse potenti.\n" +
        "Vestibulum vel diam nec felis sodales porta nec sit amet eros. Quisque sit amet molestie risus. Pellentesque turpis ante, aliquam at urna vel, pulvinar fermentum massa. Proin posuere eu erat id condimentum. Nulla imperdiet erat a varius laoreet. Curabitur sollicitudin urna non commodo condimentum. Ut id ligula in ligula maximus pulvinar et id eros. Fusce et consequat orci. Maecenas leo sem, tristique quis justo nec, accumsan interdum quam. Nunc imperdiet scelerisque iaculis. Praesent sollicitudin purus et purus porttitor volutpat. Duis tincidunt, ipsum vel dignissim imperdiet, ligula nisi ultrices velit, at sodales felis urna at mi. Donec arcu ligula, pulvinar non posuere vel, accumsan eget lorem. Vivamus ac iaculis enim, ut rutrum felis. Praesent non ultrices nibh. Proin tristique, nibh id viverra varius, orci nisi faucibus turpis, quis suscipit sem nisi eu purus.";

    private final static String VERY_HUGE_TEXT = IntStream.rangeClosed(1, 1000).mapToObj(a -> HUGE_TEXT).collect(Collectors.joining("\n"));
}
