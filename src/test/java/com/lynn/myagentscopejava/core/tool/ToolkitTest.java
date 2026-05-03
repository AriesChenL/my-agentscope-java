package com.lynn.myagentscopejava.core.tool;

import com.lynn.myagentscopejava.core.message.ToolResultBlock;
import com.lynn.myagentscopejava.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolkitTest {

    public static class Sample {
        @Tool(description = "Add two ints")
        public int add(@ToolParam(description = "first") int a,
                       @ToolParam(description = "second") int b) {
            return a + b;
        }

        @Tool(name = "greet", description = "Say hello")
        public String hello(@ToolParam(description = "name") String name) {
            return "Hello " + name;
        }

        @Tool(description = "Throws on purpose")
        public String boom() {
            throw new IllegalStateException("kaboom");
        }
    }

    @Test
    void registerScansAllToolMethods() {
        Toolkit kit = new Toolkit().registerObject(new Sample());
        assertEquals(3, kit.getSchemas().size());
        assertTrue(kit.getSchemas().stream().anyMatch(s -> s.name().equals("add")));
        assertTrue(kit.getSchemas().stream().anyMatch(s -> s.name().equals("greet")));
    }

    @Test
    void schemaHasJsonSchemaShape() {
        Toolkit kit = new Toolkit().registerObject(new Sample());
        ToolSchema add = kit.getSchemas().stream()
                .filter(s -> s.name().equals("add")).findFirst().orElseThrow();
        Map<String, Object> params = add.parameters();
        assertEquals("object", params.get("type"));
        Map<?, ?> props = (Map<?, ?>) params.get("properties");
        assertEquals("integer", ((Map<?, ?>) props.get("a")).get("type"));
        assertTrue(((java.util.List<?>) params.get("required")).contains("a"));
    }

    @Test
    void invokeRunsTheMethodAndStringifiesResult() {
        Toolkit kit = new Toolkit().registerObject(new Sample());
        ToolResultBlock r = kit.invoke(new ToolUseBlock("c1", "add", Map.of("a", 7, "b", 4)));
        assertEquals("11", r.output());
        assertFalse(r.isError());

        ToolResultBlock g = kit.invoke(new ToolUseBlock("c2", "greet", Map.of("name", "Lin")));
        assertEquals("Hello Lin", g.output());
    }

    @Test
    void unknownToolReturnsError() {
        Toolkit kit = new Toolkit();
        ToolResultBlock r = kit.invoke(new ToolUseBlock("c1", "nope", Map.of()));
        assertTrue(r.isError());
    }

    @Test
    void thrownToolBecomesErrorResult() {
        Toolkit kit = new Toolkit().registerObject(new Sample());
        ToolResultBlock r = kit.invoke(new ToolUseBlock("c1", "boom", Map.of()));
        assertTrue(r.isError());
        assertTrue(r.output().contains("kaboom"));
    }

    @Test
    void duplicateToolNameThrows() {
        Toolkit kit = new Toolkit().registerObject(new Sample());
        assertThrows(IllegalStateException.class, () -> kit.registerObject(new Sample()));
    }
}
