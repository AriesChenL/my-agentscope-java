# 第 6 章 · Tool 系统

> 目标：理解 `@Tool` 注解、JSON Schema 自动生成、`Toolkit` 注册与调用，以及"工具结果回填"的完整往返。

## 6.1 一个最小工具

```java
@Component
public class CalculatorTool implements ToolProvider {

    @Tool(description = "对两个整数求和")
    public int add(@ToolParam(description = "加数 a") int a,
                   @ToolParam(description = "加数 b") int b) {
        return a + b;
    }
}
```

5 个关键点：

1. **`@Component`** —— 注册为 Spring bean，被 `AgentAutoConfiguration.toolkit()` 收集
2. **`implements ToolProvider`** —— marker 接口（无方法），用来"挑出工具 bean"，避免循环依赖（详见 6.7）
3. **`@Tool(description = "...")`** —— 标记方法可被 LLM 调用，描述给模型看
4. **方法返回值** —— 任意类型，框架转 `String` 回传给模型
5. **`@ToolParam`** —— 描述每个参数

启动后 Toolkit 里就多了一个名为 `add` 的工具，自动 schema：

```json
{
  "name": "add",
  "description": "对两个整数求和",
  "parameters": {
    "type": "object",
    "properties": {
      "a": {"type": "integer", "description": "加数 a"},
      "b": {"type": "integer", "description": "加数 b"}
    },
    "required": ["a", "b"]
  }
}
```

这个 schema 会随 chat 请求一起发给 LLM，告诉模型"你可以调用 `add(a, b)` 求和"。

## 6.2 `@Tool` 与 `@ToolParam` 注解

```java
@Retention(RUNTIME) @Target(METHOD)
public @interface Tool {
    String name() default "";       // 留空 = 用方法名
    String description();           // 必填
}

@Retention(RUNTIME) @Target(PARAMETER)
public @interface ToolParam {
    String name() default "";       // 留空 = 用反射读到的参数名
    String description() default "";
    boolean required() default true;
}
```

参数名读取依赖 `-parameters` 编译开关 —— **`spring-boot-starter-parent` 默认开启**，所以不用管。如果你脱离 Spring Boot 用，记得 javac 加 `-parameters`，否则反射拿到的参数名是 `arg0`。

## 6.3 反射生成 JSON Schema

`ToolSchemaGenerator.generate(Method)`：

```java
public static ToolSchema generate(Method method) {
    Tool ann = method.getAnnotation(Tool.class);
    String name = ann.name().isEmpty() ? method.getName() : ann.name();

    Map<String, Object> properties = new LinkedHashMap<>();
    List<String> required = new ArrayList<>();

    for (Parameter p : method.getParameters()) {
        ToolParam pa = p.getAnnotation(ToolParam.class);
        String paramName = (pa != null && !pa.name().isEmpty()) ? pa.name() : p.getName();
        String desc = pa != null ? pa.description() : "";
        boolean req = pa == null || pa.required();

        Map<String, Object> propSchema = buildSchemaFor(p);
        if (!desc.isEmpty()) propSchema.put("description", desc);
        properties.put(paramName, propSchema);
        if (req) required.add(paramName);
    }

    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("type", "object");
    parameters.put("properties", properties);
    if (!required.isEmpty()) parameters.put("required", required);

    return new ToolSchema(name, ann.description(), parameters);
}
```

核心是 `jsonTypeOf(Class<?>)`：

```java
if (t == String.class) return "string";
if (t == boolean.class || t == Boolean.class) return "boolean";
if (t == int.class || t == Integer.class
    || t == long.class || t == Long.class) return "integer";
if (t == double.class || t == Double.class
    || t == float.class || t == Float.class) return "number";
if (Collection.class.isAssignableFrom(t)) return "array";
if (Map.class.isAssignableFrom(t)) return "object";
if (t == Object.class) return null; // 不限定
```

支持的类型：

| Java 类型 | JSON Schema |
|----------|-------------|
| `String` | `"string"` |
| `int / long / Integer / Long` | `"integer"` |
| `double / float / Double / Float` | `"number"` |
| `boolean / Boolean` | `"boolean"` |
| `List<T> / Collection<T>` | `"array"`，items 取自泛型 T |
| `Map<String, Object>` | `"object"` |
| `Object` | 不限定（用于动态类型） |

`List<X>` 的 `X` 从 `ParameterizedType` 拿（详见 `buildSchemaFor`）。

## 6.4 `Toolkit` —— 注册与调用

`Toolkit.registerObject(Object)`：

```java
public Toolkit registerObject(Object instance) {
    for (Method m : instance.getClass().getDeclaredMethods()) {
        if (!m.isAnnotationPresent(Tool.class)) continue;
        ToolSchema schema = ToolSchemaGenerator.generate(m);
        if (tools.containsKey(schema.name())) {
            throw new IllegalStateException("工具名重复：" + schema.name());
        }
        m.setAccessible(true);
        tools.put(schema.name(), new RegisteredTool(schema, instance, m));
    }
    return this;
}
```

扫一遍 `instance` 的所有方法，找 `@Tool` 标注的，登记 `(name → RegisteredTool)`。`RegisteredTool` 是一个三元组：`schema + instance + Method`。

`Toolkit.invoke(ToolUseBlock)` —— 模型说"调 add(a=1, b=2)"，框架执行：

```java
public ToolResultBlock invoke(ToolUseBlock use) {
    RegisteredTool tool = tools.get(use.name());
    if (tool == null) {
        return ToolResultBlock.error(use.id(), use.name(), "Unknown tool: " + use.name());
    }
    try {
        Object[] args = bindArgs(tool.method(), use.input());
        Object result = tool.method().invoke(tool.instance(), args);
        return ToolResultBlock.success(use.id(), use.name(), stringify(result));
    } catch (InvocationTargetException ite) {
        Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
        if (cause instanceof ToolSuspendException tse) {
            return ToolResultBlock.pending(use.id(), use.name(),
                    tse.getReason() != null ? tse.getReason() : tse.getMessage());
        }
        return ToolResultBlock.error(use.id(), use.name(),
                cause.getClass().getSimpleName() + ": " + cause.getMessage());
    } catch (Exception e) {
        return ToolResultBlock.error(use.id(), use.name(), e.getMessage());
    }
}
```

**永不抛异常**，三种返回：

- 成功 → `success`
- 抛 `ToolSuspendException` → `pending`（HITL 触发，第 11 章详解）
- 其它异常 → `error`

`bindArgs` 做"JSON 输入 → Java 参数数组"的绑定 + 类型转换：

```java
for (int i = 0; i < params.length; i++) {
    Parameter p = params[i];
    ToolParam pa = p.getAnnotation(ToolParam.class);
    String key = (pa != null && !pa.name().isEmpty()) ? pa.name() : p.getName();
    args[i] = ToolSchemaGenerator.coerce(input.get(key), p.getType());
}
```

`coerce` 处理常见的 JSON ↔ Java 类型转换（如 `Integer` ↔ `int`、`Double` ↔ `int` 截断、`String` ↔ enum 等）。

返回值 `stringify(Object)` 简单：

- `null` → `""`
- `String` → 原样
- `Collection` → `[a, b, c]`
- 其它 → `String.valueOf(o)`

如果你要返回结构化数据（让模型读取），手动 `JSON.toString(...)` 或者直接返回 String 即可。

## 6.5 一次完整的工具调用往返

模型 → 框架 → 工具 → 框架 → 模型：

```
1. 模型生成（流式）
   chunk: toolCallDeltas[(idx=0, id="c1", name="add")]
   chunk: toolCallDeltas[(idx=0, args="{\"a\":")]
   chunk: toolCallDeltas[(idx=0, args="123,\"b\":456}")]
   chunk: finish("tool_calls", usage)

2. ChunkAccumulator.buildBlocks() 拼出
   ToolUseBlock(id="c1", name="add", input={a:123, b:456})

3. 写入 memory，作为 ASSISTANT 消息的 content

4. ReActAgent acting 阶段
   PreActingEvent fire → hook 可拦截
   toolkit.invoke(use) → CalculatorTool.add(123, 456) → 579
   PostActingEvent fire → hook 可改写

5. 包装为 ToolResultBlock.success("c1", "add", "579")

6. 写入 memory，作为 TOOL 消息

7. 下一轮 reasoning，模型看到 c1 的结果，给出最终回复
```

## 6.6 工具异常处理

业务异常**不需要 try-catch**，让框架接住：

```java
@Tool(description = "查询用户")
public String getUser(@ToolParam(description = "id") long id) {
    if (id < 0) throw new IllegalArgumentException("id 必须为正数");
    return userRepo.findById(id).orElseThrow().toString();
}
```

模型会看到工具结果是 `IllegalArgumentException: id 必须为正数`，自己尝试纠正（要么不用这个工具、要么换参数）。

唯一**特殊**的异常是 `ToolSuspendException`：

```java
@Tool(description = "扣款")
public String deduct(int amount) {
    if (amount > 10000) {
        throw new ToolSuspendException("金额 " + amount + " 元超过阈值，请人工审批");
    }
    return "已扣款 " + amount + " 元";
}
```

抛它 → 框架转为 `pending` 结果 → agent 立即返回 → 等外部"批准/拒绝"。详见第 11 章 HITL 模式 3。

## 6.7 自动注册：`ToolProvider` marker 接口

`AgentAutoConfiguration.toolkit()`：

```java
@Bean
public Toolkit toolkit(@Autowired(required = false) List<ToolProvider> toolBeans) {
    Toolkit kit = new Toolkit();
    if (toolBeans != null) toolBeans.forEach(kit::registerObject);
    return kit;
}
```

注入 `List<ToolProvider>` 让 Spring 把所有实现该接口的 bean 收集起来。

**为什么用 marker 接口而不是 `List<Object>`？**

如果用 `List<Object>`，Spring 会把容器里**所有** bean 都塞进来，包括 `ChatService`、`ChatModel` 等等 —— 形成循环依赖（`ChatService` 依赖 `Toolkit`，但 `Toolkit` 又"依赖" `ChatService`）。

marker 接口的代价是工具类必须显式 `implements ToolProvider`，但换来零侵入的依赖隔离，值。

## 6.8 实战：自己加一个工具

任务：加一个 `current_time` 工具，返回当前北京时间。

```java
@Component
public class CurrentTimeTool implements ToolProvider {

    @Tool(description = "获取当前时间（北京时区）")
    public String currentTime() {
        return java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
```

放在 `tools/` 包下，重启应用。问"现在几点？"，模型会自动调用它。

（项目里其实已经有这个工具，路径 `tools/CurrentTimeTool.java`，可对照查看。）

## 6.9 自检

- [ ] 我能说出 `@Tool` 的两个属性
- [ ] 我能解释为什么用 `ToolProvider` marker 接口
- [ ] 我能描述工具抛 `ToolSuspendException` 后框架做了什么
- [ ] 我能解释为什么 `Toolkit.invoke()` 永远不抛异常
- [ ] 我能写一个新工具并知道它被注册的全过程
