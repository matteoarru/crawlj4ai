package io.crawlj4ai.diff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Computes compact line-level unified diffs with +/-2 context lines to minimize LLM token usage.
 */
public final class MarkdownDiffComputer {

    private static final int CONTEXT_LINES = 2;
    private static final int MAX_MATRIX_CELLS = 2_500_000;
    private static final int MAX_DIFF_CHARS = 14_000;

    private MarkdownDiffComputer() {}

    public static String computeCompactDiff(String oldMarkdown, String newMarkdown) {
        List<String> oldLines = normalizeLines(oldMarkdown);
        List<String> newLines = normalizeLines(newMarkdown);

        if (oldLines.equals(newLines)) {
            return "";
        }

        if ((long) oldLines.size() * newLines.size() > MAX_MATRIX_CELLS) {
            return buildFallbackComparison(oldMarkdown, newMarkdown);
        }

        List<DiffLine> ops = computeLcsDiff(oldLines, newLines);
        boolean hasActualChange = ops.stream().anyMatch(op -> op.type != OpType.EQUAL);
        if (!hasActualChange) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < ops.size(); i++) {
            if (isNearChange(ops, i, CONTEXT_LINES)) {
                DiffLine line = ops.get(i);
                String prefix = switch (line.type) {
                    case EQUAL -> "  ";
                    case DELETE -> "- ";
                    case INSERT -> "+ ";
                };
                out.append(prefix).append(line.text).append('\n');
                if (out.length() >= MAX_DIFF_CHARS) {
                    out.append("... [diff truncated]\n");
                    break;
                }
            } else if (i > 0 && isNearChange(ops, i - 1, CONTEXT_LINES)) {
                out.append("@@ ... @@\n");
            }
        }
        return out.toString().trim();
    }

    private enum OpType { EQUAL, DELETE, INSERT }
    private record DiffLine(OpType type, String text) {}

    private static List<String> normalizeLines(String md) {
        if (md == null || md.isBlank()) return List.of();
        return Arrays.stream(md.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    private static List<DiffLine> computeLcsDiff(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int[][] dp = new int[n + 1][m + 1];

        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (a.get(i).equals(b.get(j))) {
                    dp[i][j] = 1 + dp[i + 1][j + 1];
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }

        List<DiffLine> result = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                result.add(new DiffLine(OpType.EQUAL, a.get(i)));
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                result.add(new DiffLine(OpType.DELETE, a.get(i)));
                i++;
            } else {
                result.add(new DiffLine(OpType.INSERT, b.get(j)));
                j++;
            }
        }
        while (i < n) result.add(new DiffLine(OpType.DELETE, a.get(i++)));
        while (j < m) result.add(new DiffLine(OpType.INSERT, b.get(j++)));
        return result;
    }

    private static boolean isNearChange(List<DiffLine> ops, int index, int window) {
        int start = Math.max(0, index - window);
        int end = Math.min(ops.size() - 1, index + window);
        for (int k = start; k <= end; k++) {
            if (ops.get(k).type != OpType.EQUAL) return true;
        }
        return false;
    }

    private static String buildFallbackComparison(String oldMd, String newMd) {
        int half = MAX_DIFF_CHARS / 2;
        String oldSub = oldMd.length() > half ? oldMd.substring(0, half) : oldMd;
        String newSub = newMd.length() > half ? newMd.substring(0, half) : newMd;
        return "--- PREVIOUS SNAPSHOT ---\n" + oldSub + "\n+++ CURRENT SNAPSHOT +++\n" + newSub;
    }
}
