package eu.wohlben.qits.agents.service.api;

import eu.wohlben.qits.agents.service.control.Evaluation;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What {@code GET /projects/{project}/evaluation} serves (epic qits-2521), each one registered for
 * reflection in {@link ApiWireReflection} (ApiWireReflectionTest walks this class's nested types).
 * The JSON answer is {@link EvaluationDto}; the Markdown one ({@link EvaluationMarkdown}) is
 * rendered from the same object, so the two never disagree. The figures are defined on the design
 * page "Comparison criteria"; {@code control.Evaluation} derives them.
 */
public final class EvaluationWire {

  private EvaluationWire() {}

  /** The report: the window, one figure set per model, the double-worked items, every item. */
  public record EvaluationDto(
      String project,
      Instant since,
      Instant until,
      List<ModelDto> models,
      List<String> doubleWorked,
      List<ItemDto> items) {

    static EvaluationDto of(Evaluation.Report report) {
      return new EvaluationDto(
          report.project(),
          report.since(),
          report.until(),
          report.models().stream().map(ModelDto::of).toList(),
          report.doubleWorked(),
          report.items().stream().map(ItemDto::of).toList());
    }
  }

  /** One model's figures. */
  public record ModelDto(
      String model,
      long taken,
      long refined,
      long verified,
      long dropped,
      double verifiedPerDay,
      PercentilesDto leadTimeSeconds,
      PercentilesDto implementSeconds,
      List<TokensDto> tokens,
      List<TokensDto> overheadTokens,
      PerVerifiedDto tokensPerVerified,
      long busySeconds,
      long idleSeconds,
      long downSeconds,
      double busyShare,
      Map<String, Long> waits,
      long blocks,
      Map<String, Long> requests,
      long sessionExits,
      double ciRuns,
      double ciRunsRed,
      double ciRunSeconds,
      Double ciRunSecondsPerVerified) {

    static ModelDto of(Evaluation.ModelFigures m) {
      return new ModelDto(
          m.model(),
          m.taken(),
          m.refined(),
          m.verified(),
          m.dropped(),
          m.verifiedPerDay(),
          PercentilesDto.of(m.leadTimeSeconds()),
          PercentilesDto.of(m.implementSeconds()),
          m.tokens().stream().map(TokensDto::of).toList(),
          m.overheadTokens().stream().map(TokensDto::of).toList(),
          PerVerifiedDto.of(m.tokensPerVerified()),
          m.busySeconds(),
          m.idleSeconds(),
          m.downSeconds(),
          m.busyShare(),
          m.waits(),
          m.blocks(),
          m.requests(),
          m.sessionExits(),
          m.ciRuns(),
          m.ciRunsRed(),
          m.ciRunSeconds(),
          m.ciRunSecondsPerVerified());
    }
  }

  /** p50 and p90 in whole seconds, nearest-rank; null with no data. */
  public record PercentilesDto(Long p50, Long p90) {

    static PercentilesDto of(Evaluation.Percentiles p) {
      return new PercentilesDto(p.p50(), p.p90());
    }
  }

  /** The sums for one Claude model id ({@code message.model}). */
  public record TokensDto(
      String claudeModel,
      long messages,
      long inputTokens,
      long outputTokens,
      long cacheCreationTokens,
      long cacheReadTokens) {

    static TokensDto of(Evaluation.Tokens t) {
      return new TokensDto(
          t.claudeModel(),
          t.messages(),
          t.inputTokens(),
          t.outputTokens(),
          t.cacheCreationTokens(),
          t.cacheReadTokens());
    }
  }

  /** The four sums (overhead included) per verified item; null with nothing verified. */
  public record PerVerifiedDto(
      double inputTokens, double outputTokens, double cacheCreationTokens, double cacheReadTokens) {

    static PerVerifiedDto of(Evaluation.PerVerified p) {
      return p == null
          ? null
          : new PerVerifiedDto(
              p.inputTokens(), p.outputTokens(), p.cacheCreationTokens(), p.cacheReadTokens());
    }
  }

  /** One item of the window, with the model it counts for (or BOTH). */
  public record ItemDto(
      String qualifiedId,
      String model,
      String archetype,
      Instant takenAt,
      Instant verifiedAt,
      Long leadTimeSeconds,
      long blocks,
      Map<String, Long> requests,
      double ciRuns,
      double ciRunSeconds,
      long inputTokens,
      long outputTokens,
      long cacheCreationTokens,
      long cacheReadTokens) {

    static ItemDto of(Evaluation.ItemFigures i) {
      return new ItemDto(
          i.qualifiedId(),
          i.model(),
          i.archetype(),
          i.takenAt(),
          i.verifiedAt(),
          i.leadTimeSeconds(),
          i.blocks(),
          i.requests(),
          i.ciRuns(),
          i.ciRunSeconds(),
          i.inputTokens(),
          i.outputTokens(),
          i.cacheCreationTokens(),
          i.cacheReadTokens());
    }
  }
}
