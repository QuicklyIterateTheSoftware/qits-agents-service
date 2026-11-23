package eu.wohlben.qits.agents.service.api;

import eu.wohlben.qits.agents.service.events.ProjectAgentChanged;
import eu.wohlben.qits.agents.service.idp.IdpTokensApi;
import eu.wohlben.qits.agents.service.projects.ProjectsApi;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native-image reflection registration for every type Jackson touches on this API, for the event
 * the bus serializes and for the outbound clients' bodies and answers. Several of them ride in
 * {@code Response.entity(...)}, which hides the type from the build-time analysis: in the native
 * binary that is a 500 behind a green JVM suite.
 *
 * <p><b>A new served type joins this list in the commit that adds it</b>; ApiWireReflectionTest
 * fails naming any nested type of {@link AgentsWire} and any record of {@link ProjectsApi} or
 * {@link IdpTokensApi} left out.
 */
@RegisterForReflection(
    targets = {
      AgentsWire.AgentSummaryDto.class,
      AgentsWire.AgentDto.class,
      AgentsWire.ClaimDto.class,
      AgentsWire.TurnDto.class,
      AgentsWire.LaunchDto.class,
      AgentsWire.ReportRequest.class,
      AgentsWire.MeDto.class,
      AgentsWire.ErrorBody.class,
      ProjectAgentChanged.class,
      // qits-eventstream serializes with its own ObjectMapper, so nothing registers its PUT body or
      // the frames it reads for us: without these the native image publishes nothing, silently.
      EventEnvelope.class,
      EventFrame.class,
      // The outbound clients' bodies and answers.
      ProjectsApi.ProjectList.class,
      ProjectsApi.Entry.class,
      ProjectsApi.Project.class,
      IdpTokensApi.Commission.class,
      IdpTokensApi.Commissioned.class
    },
    classNames = {
      "eu.wohlben.qits.eventstream.control.EventPage",
      "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin"
    })
public final class ApiWireReflection {

  private ApiWireReflection() {}
}
