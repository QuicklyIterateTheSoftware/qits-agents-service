package eu.wohlben.qits.agents.service.api;

import eu.wohlben.qits.agents.service.api.EvaluationWire.EvaluationDto;
import eu.wohlben.qits.agents.service.api.EvaluationWire.ItemDto;
import eu.wohlben.qits.agents.service.api.EvaluationWire.ModelDto;
import eu.wohlben.qits.agents.service.api.EvaluationWire.TokensDto;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The evaluation as a Markdown document ({@code Accept: text/markdown}), rendered from the very
 * {@link EvaluationDto} the JSON answer serializes: a heading with the window, the figures with the
 * models as columns, the tokens by Claude model, the double-worked items, and every item in a
 * collapsed table. Row names are the JSON field names.
 */
final class EvaluationMarkdown {

  static final String MEDIA_TYPE = "text/markdown";

  private EvaluationMarkdown() {}

  static String render(EvaluationDto report) {
    StringBuilder md = new StringBuilder();
    md.append("# Evaluation of ").append(report.project()).append("\n\n");
    md.append("Window: ")
        .append(report.since())
        .append(" to ")
        .append(report.until())
        .append(" (taken at or after the first, before the second).\n\n");

    List<ModelDto> models = report.models();
    md.append("| Figure |");
    models.forEach(m -> md.append(' ').append(m.model()).append(" |"));
    md.append("\n|---|");
    models.forEach(m -> md.append("---:|"));
    md.append('\n');
    row(md, models, "taken", m -> String.valueOf(m.taken()));
    row(md, models, "refined", m -> String.valueOf(m.refined()));
    row(md, models, "verified", m -> String.valueOf(m.verified()));
    row(md, models, "dropped", m -> String.valueOf(m.dropped()));
    row(md, models, "verifiedPerDay", m -> number(m.verifiedPerDay()));
    row(md, models, "leadTimeSeconds.p50", m -> value(m.leadTimeSeconds().p50()));
    row(md, models, "leadTimeSeconds.p90", m -> value(m.leadTimeSeconds().p90()));
    row(md, models, "implementSeconds.p50", m -> value(m.implementSeconds().p50()));
    row(md, models, "implementSeconds.p90", m -> value(m.implementSeconds().p90()));
    row(md, models, "tokensPerVerified.inputTokens",
        m -> m.tokensPerVerified() == null ? "-" : number(m.tokensPerVerified().inputTokens()));
    row(md, models, "tokensPerVerified.outputTokens",
        m -> m.tokensPerVerified() == null ? "-" : number(m.tokensPerVerified().outputTokens()));
    row(md, models, "tokensPerVerified.cacheCreationTokens",
        m -> m.tokensPerVerified() == null ? "-" : number(m.tokensPerVerified().cacheCreationTokens()));
    row(md, models, "tokensPerVerified.cacheReadTokens",
        m -> m.tokensPerVerified() == null ? "-" : number(m.tokensPerVerified().cacheReadTokens()));
    row(md, models, "busySeconds", m -> String.valueOf(m.busySeconds()));
    row(md, models, "idleSeconds", m -> String.valueOf(m.idleSeconds()));
    row(md, models, "downSeconds", m -> String.valueOf(m.downSeconds()));
    row(md, models, "busyShare", m -> number(m.busyShare()));
    for (String state : models.get(0).waits().keySet()) {
      row(md, models, "waits." + state, m -> value(m.waits().get(state)));
    }
    row(md, models, "blocks", m -> String.valueOf(m.blocks()));
    for (String state : models.get(0).requests().keySet()) {
      row(md, models, "requests." + state, m -> value(m.requests().get(state)));
    }
    row(md, models, "sessionExits", m -> String.valueOf(m.sessionExits()));
    row(md, models, "ciRuns", m -> number(m.ciRuns()));
    row(md, models, "ciRunsRed", m -> number(m.ciRunsRed()));
    row(md, models, "ciRunSeconds", m -> number(m.ciRunSeconds()));
    row(md, models, "ciRunSecondsPerVerified", m -> value(m.ciRunSecondsPerVerified()));

    md.append("\n## Tokens by Claude model\n\n");
    md.append(
        "| Model | Claude model | Kind | messages | inputTokens | outputTokens |"
            + " cacheCreationTokens | cacheReadTokens |\n");
    md.append("|---|---|---|---:|---:|---:|---:|---:|\n");
    boolean any = false;
    for (ModelDto model : models) {
      any |= tokens(md, model.model(), "item", model.tokens());
      any |= tokens(md, model.model(), "overhead", model.overheadTokens());
    }
    if (!any) {
      md.append("| - | - | - | 0 | 0 | 0 | 0 | 0 |\n");
    }

    md.append("\n## Double-worked\n\n");
    if (report.doubleWorked().isEmpty()) {
      md.append("None.\n");
    } else {
      report.doubleWorked().forEach(q -> md.append("- ").append(q).append('\n'));
    }

    md.append("\n<details>\n<summary>Items (").append(report.items().size()).append(")</summary>\n\n");
    md.append(
        "| qualifiedId | model | archetype | takenAt | verifiedAt | leadTimeSeconds | blocks |"
            + " requests | ciRuns | ciRunSeconds | inputTokens | outputTokens |"
            + " cacheCreationTokens | cacheReadTokens |\n");
    md.append("|---|---|---|---|---|---:|---:|---|---:|---:|---:|---:|---:|---:|\n");
    for (ItemDto item : report.items()) {
      md.append("| ")
          .append(item.qualifiedId())
          .append(" | ")
          .append(item.model())
          .append(" | ")
          .append(item.archetype())
          .append(" | ")
          .append(item.takenAt())
          .append(" | ")
          .append(value(item.verifiedAt()))
          .append(" | ")
          .append(value(item.leadTimeSeconds()))
          .append(" | ")
          .append(item.blocks())
          .append(" | ")
          .append(requests(item.requests()))
          .append(" | ")
          .append(number(item.ciRuns()))
          .append(" | ")
          .append(number(item.ciRunSeconds()))
          .append(" | ")
          .append(item.inputTokens())
          .append(" | ")
          .append(item.outputTokens())
          .append(" | ")
          .append(item.cacheCreationTokens())
          .append(" | ")
          .append(item.cacheReadTokens())
          .append(" |\n");
    }
    md.append("\n</details>\n");
    return md.toString();
  }

  private static void row(
      StringBuilder md, List<ModelDto> models, String name, Function<ModelDto, String> value) {
    md.append("| ").append(name).append(" |");
    models.forEach(m -> md.append(' ').append(value.apply(m)).append(" |"));
    md.append('\n');
  }

  private static boolean tokens(StringBuilder md, String model, String kind, List<TokensDto> rows) {
    for (TokensDto t : rows) {
      md.append("| ")
          .append(model)
          .append(" | ")
          .append(t.claudeModel())
          .append(" | ")
          .append(kind)
          .append(" | ")
          .append(t.messages())
          .append(" | ")
          .append(t.inputTokens())
          .append(" | ")
          .append(t.outputTokens())
          .append(" | ")
          .append(t.cacheCreationTokens())
          .append(" | ")
          .append(t.cacheReadTokens())
          .append(" |\n");
    }
    return !rows.isEmpty();
  }

  private static String requests(Map<String, Long> requests) {
    if (requests.isEmpty()) {
      return "-";
    }
    StringBuilder out = new StringBuilder();
    requests.forEach(
        (state, count) -> out.append(out.length() == 0 ? "" : ", ").append(state).append(' ').append(count));
    return out.toString();
  }

  private static String value(Object value) {
    if (value == null) {
      return "-";
    }
    return value instanceof Double d ? number(d) : String.valueOf(value);
  }

  private static String number(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }
}
