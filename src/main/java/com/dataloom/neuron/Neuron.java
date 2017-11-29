/*
 * Copyright (C) 2017. Kryptnostic, Inc (dba Loom)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * You can contact the owner of the copyright at support@thedataloom.com
 */

package com.dataloom.neuron;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

import com.dataloom.data.DataGraphManager;
import com.dataloom.data.EntityKey;
import com.dataloom.data.EntityKeyIdService;
import com.dataloom.neuron.audit.AuditEntitySetUtils;
import com.dataloom.neuron.audit.AuditLogQueryService;
import com.dataloom.neuron.receptors.Receptor;
import com.dataloom.neuron.signals.AuditableSignal;
import com.dataloom.neuron.signals.Signal;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.kryptnostic.rhizome.configuration.cassandra.CassandraConfiguration;

import static com.google.common.base.Preconditions.checkNotNull;

public class Neuron {

    private static final Logger logger = LoggerFactory.getLogger( Neuron.class );

    private final AuditLogQueryService auditLogQueryService;
    private final DataGraphManager     dataGraphManager;
    private final EntityKeyIdService   entityKeyIdService;

    private final EnumMap<SignalType, Set<Receptor>> receptors = Maps.newEnumMap( SignalType.class );

    public Neuron(
            DataGraphManager dataGraphManager,
            EntityKeyIdService entityKeyIdService,
            HikariDataSource hds ) {

        this.auditLogQueryService = new AuditLogQueryService( hds );

        this.dataGraphManager = dataGraphManager;
        this.entityKeyIdService = entityKeyIdService;
    }

    public void activateReceptor( EnumSet<SignalType> types, Receptor receptor ) {

        checkNotNull( types );
        checkNotNull( receptor );

        types.forEach( type -> {
            if ( receptors.containsKey( type ) ) {
                receptors.get( type ).add( receptor );
            } else {
                receptors.put( type, Sets.newHashSet( receptor ) );
            }
        } );
    }

    @Async
    public void transmit( Signal signal ) {

        // 1. write to the Audit EntitySet
        UUID auditId = writeToAuditEntitySet( signal );

        if ( auditId != null ) {

            // 2. write to the Audit Log Table
            writeToAuditLog( signal, auditId );
        }

        // 3. hand off event to receptors
        Set<Receptor> receptors = this.receptors.get( signal.getType() );

        // TODO: does order matter?
        // TODO: what about parallelization?
        receptors.forEach( receptor -> receptor.process( signal ) );
    }

    private UUID writeToAuditEntitySet( Signal signal ) {

        try {

            UUID auditEntitySetId = AuditEntitySetUtils.getId();
            UUID auditEntitySetSyncId = AuditEntitySetUtils.getSyncId();
            String auditEntityId = UUID.randomUUID().toString();

            this.dataGraphManager.createEntities(
                    auditEntitySetId,
                    auditEntitySetSyncId,
                    AuditEntitySetUtils.prepareAuditEntityData( signal, auditEntityId ),
                    AuditEntitySetUtils.getPropertyDataTypesMap()
            );

            // TODO: remove dependency on EntityKeyIdService once DataGraphManager can return the UUID after creation
            EntityKey auditEntityKey = new EntityKey( auditEntitySetId, auditEntityId, auditEntitySetSyncId );
            return entityKeyIdService.getEntityKeyId( auditEntityKey );

        } catch ( ExecutionException | InterruptedException | NullPointerException e ) {
            logger.error( "Failed to write to Audit EntitySet." );
            logger.error( e.getMessage(), e );
            return null;
        }
    }

    private void writeToAuditLog( Signal signal, UUID auditId ) {

        // TODO: still need to figure out dataId and blockId
        AuditableSignal auditableSignal = new AuditableSignal(
                signal.getType(),
                signal.getAclKey(),
                signal.getPrincipal(),
                signal.getDetails(),
                auditId,
                UUIDs.timeBased(),
                null,
                null
        );

        // TODO: still needs to be implemented
        this.auditLogQueryService.store( auditableSignal );
    }
}
