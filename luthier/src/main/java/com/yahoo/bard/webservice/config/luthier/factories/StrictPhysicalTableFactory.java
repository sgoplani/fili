// Copyright 2019 Oath Inc.
// Licensed under the terms of the Apache license. Please see LICENSE.md file distributed with this work for terms.
package com.yahoo.bard.webservice.config.luthier.factories;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yahoo.bard.webservice.config.luthier.LuthierIndustrialPark;
import com.yahoo.bard.webservice.table.PhysicalTable;
import com.yahoo.bard.webservice.table.StrictPhysicalTable;

/**
 * A factory that is used by default to support Simple (non-Composite) Physical Table.
 */
public class StrictPhysicalTableFactory extends SingleDataSourcePhysicalTableFactory {

    /**
     * Build a StrictPhysicalTable instance.
     *
     * @param name  the config dictionary name (normally the apiName)
     * @param configTable  the json tree describing this config entity
     * @param resourceFactories  the source for locating dependent objects
     *
     * @return  A newly constructed config instance for the name and config provided
     */
    @Override
    public PhysicalTable build(String name, ObjectNode configTable, LuthierIndustrialPark resourceFactories) {
        super.prepare(name, configTable, resourceFactories);
        return new StrictPhysicalTable(
                this.tableName,
                this.timeGrain,
                this.columns,
                this.logicalToPhysicalColumnNames,
                this.metadataService
        );
    }
}
