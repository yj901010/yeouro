package ai.yeouro.integration;

import ai.yeouro.api.ApiModels.NavigationStatus;

/** Boundary for a future verified device/SDK integration, not a simulated success provider. */
public interface NavigationGateway {
    NavigationStatus status();
    boolean canConfirmRealApplication();
}
