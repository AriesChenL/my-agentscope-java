package com.lynn.myagentscopejava.core.cluster;

import com.lynn.myagentscopejava.core.cluster.impl.LocalNotificationBus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalNotificationBusTest {

    @Test
    void publishWithNoSubscriberIsNoop() {
        LocalNotificationBus bus = new LocalNotificationBus();
        bus.publish("ch", "hello");   // 不抛
        assertEquals(0, bus.subscriberCount("ch"));
    }

    @Test
    void subscribeReceivesPublishedMessages() {
        LocalNotificationBus bus = new LocalNotificationBus();
        List<String> received = new CopyOnWriteArrayList<>();
        try (var sub = bus.subscribe("ch", received::add)) {
            bus.publish("ch", "a");
            bus.publish("ch", "b");
            assertEquals(List.of("a", "b"), received);
        }
    }

    @Test
    void unsubscribeStopsReceiving() {
        LocalNotificationBus bus = new LocalNotificationBus();
        List<String> received = new ArrayList<>();
        var sub = bus.subscribe("ch", received::add);
        bus.publish("ch", "a");
        sub.close();
        bus.publish("ch", "b");
        assertEquals(List.of("a"), received);
        assertEquals(0, bus.subscriberCount("ch"));
    }

    @Test
    void multipleSubscribersAllReceive() {
        LocalNotificationBus bus = new LocalNotificationBus();
        List<String> r1 = new CopyOnWriteArrayList<>();
        List<String> r2 = new CopyOnWriteArrayList<>();
        try (var s1 = bus.subscribe("ch", r1::add);
             var s2 = bus.subscribe("ch", r2::add)) {
            bus.publish("ch", "x");
            assertEquals(List.of("x"), r1);
            assertEquals(List.of("x"), r2);
            assertEquals(2, bus.subscriberCount("ch"));
        }
    }

    @Test
    void differentChannelsAreIsolated() {
        LocalNotificationBus bus = new LocalNotificationBus();
        List<String> rA = new CopyOnWriteArrayList<>();
        List<String> rB = new CopyOnWriteArrayList<>();
        try (var sA = bus.subscribe("A", rA::add);
             var sB = bus.subscribe("B", rB::add)) {
            bus.publish("A", "hello-a");
            bus.publish("B", "hello-b");
            assertEquals(List.of("hello-a"), rA);
            assertEquals(List.of("hello-b"), rB);
        }
    }

    @Test
    void handlerExceptionDoesNotAffectOtherSubscribers() {
        LocalNotificationBus bus = new LocalNotificationBus();
        List<String> r2 = new CopyOnWriteArrayList<>();
        try (var s1 = bus.subscribe("ch", m -> { throw new RuntimeException("boom"); });
             var s2 = bus.subscribe("ch", r2::add)) {
            bus.publish("ch", "x");   // 不该抛
            assertEquals(List.of("x"), r2);
        }
    }

    @Test
    void closeSubscriptionIsIdempotent() {
        LocalNotificationBus bus = new LocalNotificationBus();
        var sub = bus.subscribe("ch", m -> {});
        sub.close();
        sub.close();
        sub.close();
        assertEquals(0, bus.subscriberCount("ch"));
    }
}
