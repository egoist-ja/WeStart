package com.westart.ai.westart.service.tool;

import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 美团酒旅工具。
 *
 * 通过美团官方 CLI（@meituan-travel/ht-ai）提供酒店、机票、火车票、
 * 景点门票、度假等旅游出行查询与预订能力。
 * Token 从环境变量 {@code MEI_APIKEY} 读取，以 {@code MEITUAN_HT_TOKEN}
 * 传递给 CLI 子进程。
 *
 * 调用链路：
 * 用户自然语言 → AI 提取意图 → {@link #travelQuery} 执行 CLI
 * → 等待 1-2 分钟 → 返回 Markdown 格式结果 → AI 解析并回复
 */
@Service
@Slf4j
public class MeituanTravelTool {

    private static final long TIMEOUT_MINUTES = 3;
    private static final String CHANNEL = "meituan-developer";
    private static final boolean IS_WINDOWS = System.getProperty("os.name")
            .toLowerCase().contains("win");

    /**
     * 从环境变量获取美团酒旅 Token。
     *
     * @return 美团酒旅 API Token
     * @throws IllegalStateException 环境变量未设置时抛出
     */
    private String getToken() {
        String token = System.getenv("MEI_APIKEY");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("环境变量 MEI_APIKEY 未设置");
        }
        return token;
    }

    /**
     * 美团酒旅综合查询。
     *
     * 覆盖场景：酒店推荐与预订、机票/火车票查询、景点门票、
     * 行程规划、度假跟团等所有旅游出行需求。
     *
     * @param query 用户的自然语言旅游查询（必填，越具体推荐越精准）
     * @param city  用户所在城市或目的地（选填，默认北京）
     * @return 美团酒旅服务返回的 Markdown 格式结果
     */
    @Tool("美团酒旅官方服务，覆盖酒店、机票、火车票、景点门票、度假查询与预订。" +
          "当用户有旅游出行需求时调用此工具。" +
          "query=用户的自然语言旅游查询（必填，越具体推荐越精准），" +
          "city=用户所在城市或目的地（选填，默认北京）")
    public String travelQuery(String query, String city) {
        String token = getToken();
        String resolvedCity = (city != null && !city.isBlank()) ? city : "北京";

        List<String> command = buildCommand(query, resolvedCity);

        log.info("美团酒旅查询开始，query={}，city={}，预计耗时1-2分钟",
                query, resolvedCity);

        long startNanos = System.nanoTime();
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().put("MEITUAN_HT_TOKEN", token);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // 读取 CLI 输出
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.error("美团酒旅查询超时，query={}，超时时间={}分钟",
                        query, TIMEOUT_MINUTES);
                throw new RuntimeException("美团酒旅查询超时（"
                        + TIMEOUT_MINUTES + "分钟），当前查询人数较多，请稍后再试");
            }

            int exitCode = process.exitValue();
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

            if (exitCode == 3) {
                log.error("美团酒旅 Token 鉴权失败，请检查 MEI_APIKEY 环境变量");
                throw new IllegalStateException(
                        "美团酒旅 Token 鉴权失败，请检查 MEI_APIKEY 环境变量是否正确配置");
            }
            if (exitCode != 0) {
                log.error("美团酒旅查询失败，exitCode={}，elapsedMs={}，output={}",
                        exitCode, elapsedMs, output);
                throw new RuntimeException("美团酒旅查询失败（exitCode="
                        + exitCode + "），建议换个问法重试");
            }

            log.info("美团酒旅查询成功，响应长度={}，elapsedMs={}",
                    output.length(), elapsedMs);

            if (output.isBlank()) {
                return "暂无相关结果，建议调整查询关键词后重试。";
            }

            return output;

        } catch (IOException e) {
            log.error("美团酒旅 CLI 执行失败，query={}，请确认 Node.js 和 npm 已安装", query, e);
            throw new RuntimeException(
                    "美团酒旅查询失败：无法执行命令，请确认 Node.js 和 npm 已安装", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("美团酒旅查询被中断，query={}", query);
            throw new RuntimeException("美团酒旅查询被中断", e);
        }
    }

    /**
     * 构建 CLI 命令参数列表。
     *
     * 优先使用项目本地 node_modules/.bin/ 下的 ht-ai，
     * 未安装时自动回退到 npx（首次自动下载）。
     *
     * @param query 用户自然语言查询
     * @param city  城市名称
     * @return 命令参数列表，可直接传入 ProcessBuilder
     */
    private List<String> buildCommand(String query, String city) {
        List<String> command = new ArrayList<>();

        if (IS_WINDOWS) {
            command.add("cmd");
            command.add("/c");
        }

        // 优先使用本地安装的 ht-ai（npm install 后即存在）
        String binaryPath = resolveHtAiBinary();
        command.add(binaryPath);

        command.add("query");
        command.add("--query");
        command.add(query);
        command.add("--origin-query");
        command.add(query);
        command.add("--channel");
        command.add(CHANNEL);
        command.add("--city");
        command.add(city);
        return command;
    }

    /**
     * 解析 ht-ai CLI 的可执行路径。
     *
     * 查找顺序：项目本地 node_modules → 全局 ht-ai → npx 兜底。
     * 队友只需安装 Node.js 后执行一次 {@code npm install} 即可。
     *
     * @return 可用的 ht-ai 命令或路径
     */
    private String resolveHtAiBinary() {
        Path projectRoot = Path.of(System.getProperty("user.dir"));
        Path localBinDir = projectRoot.resolve("node_modules").resolve(".bin");

        // 在 node_modules/.bin 下查找（Windows 为 .cmd，Unix 为无后缀脚本）
        if (Files.isDirectory(localBinDir)) {
            try {
                for (Path candidate : Files.list(localBinDir).toList()) {
                    String name = candidate.getFileName().toString();
                    if (name.equals("ht-ai")
                            || name.equals("ht-ai.cmd")
                            || name.equals("ht-ai.ps1")) {
                        log.info("使用本地 ht-ai：{}", candidate);
                        return candidate.toString();
                    }
                }
            } catch (IOException ignored) {
                // 遍历失败则走回退
            }
        }

        // 回退到 npx（自动使用本地已安装的包，或临时下载）
        log.info("未找到本地 ht-ai，回退到 npx。请执行 npm install 安装本地依赖以加快启动速度");
        return "npx @meituan-travel/ht-ai@latest";
    }

}
