package ai.yeouro.integration;

import ai.yeouro.api.ApiModels.NavigationStatus;
import org.springframework.stereotype.Component;

@Component
public class UnavailableNavigationGateway implements NavigationGateway {
    @Override public NavigationStatus status() { return new NavigationStatus("PLEOS", "UNAVAILABLE", "SDK_NOT_CONNECTED"); }
    @Override public boolean canConfirmRealApplication() { return false; }
}
