package com.rkghrud.shipapp.widget;

import com.rkghrud.shipapp.R;

public class ShipmentCompactWidget extends ShipmentWidget {

    @Override
    protected int layoutId() {
        return R.layout.widget_shipment_compact;
    }

    @Override
    protected boolean showsTotalCount() {
        return false;
    }
}
