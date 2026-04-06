package com.rkghrud.shipapp.data;

import java.util.Collections;
import java.util.List;

public class ShipmentDashboardSnapshot {
    private final List<ShipmentSummary> shipments;
    private final int connectedSourceCount;
    private final int fetchedSourceCount;
    private final String heroMessage;
    private final String homeCafe24Status;
    private final String prepareCafe24Status;
    private final String coupangStatus;

    public ShipmentDashboardSnapshot(
            List<ShipmentSummary> shipments,
            int connectedSourceCount,
            int fetchedSourceCount,
            String heroMessage,
            String homeCafe24Status,
            String prepareCafe24Status,
            String coupangStatus
    ) {
        this.shipments = shipments;
        this.connectedSourceCount = connectedSourceCount;
        this.fetchedSourceCount = fetchedSourceCount;
        this.heroMessage = heroMessage;
        this.homeCafe24Status = homeCafe24Status;
        this.prepareCafe24Status = prepareCafe24Status;
        this.coupangStatus = coupangStatus;
    }

    public List<ShipmentSummary> getShipments() {
        return Collections.unmodifiableList(shipments);
    }

    public int getConnectedSourceCount() {
        return connectedSourceCount;
    }

    public int getFetchedSourceCount() {
        return fetchedSourceCount;
    }

    public int getActionCount() {
        int count = 0;
        for (ShipmentSummary shipment : shipments) {
            if (shipment.requiresAction()) {
                count++;
            }
        }
        return count;
    }

    public int getTotalCount() {
        return shipments.size();
    }

    public String getHeroMessage() {
        return heroMessage;
    }

    public String getHomeCafe24Status() {
        return homeCafe24Status;
    }

    public String getPrepareCafe24Status() {
        return prepareCafe24Status;
    }

    public String getCoupangStatus() {
        return coupangStatus;
    }
}
