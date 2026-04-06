package com.rkghrud.shipapp.data;

public interface ShipmentRepository {
    ShipmentDashboardSnapshot fetchDashboard() throws Exception;
}
