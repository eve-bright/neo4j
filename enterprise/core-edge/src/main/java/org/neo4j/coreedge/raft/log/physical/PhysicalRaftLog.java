/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.coreedge.raft.log.physical;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.neo4j.coreedge.raft.log.RaftLog;
import org.neo4j.coreedge.raft.log.RaftLogCompactedException;
import org.neo4j.coreedge.raft.log.RaftLogCursor;
import org.neo4j.coreedge.raft.log.RaftLogEntry;
import org.neo4j.coreedge.raft.log.RaftLogMetadataCache;
import org.neo4j.coreedge.raft.replication.ReplicatedContent;
import org.neo4j.coreedge.raft.state.ChannelMarshal;
import org.neo4j.cursor.CursorValue;
import org.neo4j.cursor.IOCursor;
import org.neo4j.helpers.Reference;
import org.neo4j.helpers.collection.LruCache;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.impl.transaction.log.FlushablePositionAwareChannel;
import org.neo4j.kernel.impl.transaction.log.LogFileInformation;
import org.neo4j.kernel.impl.transaction.log.LogHeaderCache;
import org.neo4j.kernel.impl.transaction.log.LogPosition;
import org.neo4j.kernel.impl.transaction.log.LogPositionMarker;
import org.neo4j.kernel.impl.transaction.log.LoggingLogFileMonitor;
import org.neo4j.kernel.impl.transaction.log.PhysicalLogFile;
import org.neo4j.kernel.impl.transaction.log.ReadableLogChannel;
import org.neo4j.kernel.impl.transaction.log.entry.LogHeader;
import org.neo4j.kernel.impl.transaction.log.pruning.LogPruneStrategy;
import org.neo4j.kernel.impl.transaction.log.pruning.LogPruning;
import org.neo4j.kernel.impl.transaction.log.pruning.LogPruningImpl;
import org.neo4j.kernel.impl.transaction.log.rotation.LogRotation;
import org.neo4j.kernel.impl.transaction.log.rotation.LogRotationImpl;
import org.neo4j.kernel.impl.transaction.tracing.LogAppendEvent;
import org.neo4j.kernel.internal.DatabaseHealth;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.logging.Log;
import org.neo4j.logging.LogProvider;

import static java.lang.String.format;
import static org.neo4j.coreedge.raft.log.physical.pruning.RaftLogPruneStrategyFactory.fromConfigValue;

// TODO: Handle recovery better; e.g add missing continuation records
// TODO: Better caching; e.g divide per log version, allow searching for non-exact start point, cache when closing cursor ...

/**
 * The physical RAFT log is an append only log supporting the operations required to support
 * the RAFT consensus algorithm. The physical part relates to the fact that the implementation
 * borrows from infrastructure around the already existing {@link PhysicalLogFile} and related.
 *
 * A RAFT log must be able to append new entries, but also truncate not yet committed entries,
 * prune out old compacted entries and skip to a later starting point.
 *
 * The RAFT log consists of a sequence of individual log files with the following format:
 *     [HEADER] [CONTINUATION RECORD] [APPEND/COMMIT RECORDS]*
 *
 * Later log files are said to have a higher log version. This terminology is slightly confusing
 * but inherited, so we stick with it. The version is not about the format itself, which is specifically
 * referred to as a log format version.
 *
 * New log files are created when truncating, skipping or when appending and the file size has
 * reached over the configured rotation threshold. This is called rotation. Each log file begins
 * with a header and a continuation record and then follows a sequence of append and commit records.
 */
public class PhysicalRaftLog implements RaftLog, Lifecycle
{
    public static final String PHYSICAL_LOG_DIRECTORY_NAME = "raft-log";

    private final PhysicalRaftLogFiles logFiles;
    private final PhysicalRaftLogFile logFile;
    private final ChannelMarshal<ReplicatedContent> marshal;
    private final Supplier<DatabaseHealth> databaseHealthSupplier;
    private final Log log;
    private LogRotation logRotation;
    private FlushablePositionAwareChannel writer;
    private final RaftLogMetadataCache metadataCache;
    private long prevIndex = -1;
    private long prevTerm = -1;
    private final AtomicLong appendIndex = new AtomicLong( -1 );
    private long commitIndex = -1;
    private final LogPositionMarker positionMarker = new LogPositionMarker();
    private long term = -1;

    private final RaftEntryStore entryStore;
    private final LruCache<Long,RaftLogEntry> entryCache;
    private final LogPruning logPruning;

    public PhysicalRaftLog( FileSystemAbstraction fileSystem, File directory, long rotateAtSize, String pruningConf,
                            int entryCacheSize, int headerCacheSize,
                            PhysicalRaftLogFile.Monitor monitor, ChannelMarshal<ReplicatedContent> marshal,
                            Supplier<DatabaseHealth> databaseHealthSupplier, LogProvider logProvider,
                            RaftLogMetadataCache raftLogMetadataCache )
    {
        this.marshal = marshal;
        this.databaseHealthSupplier = databaseHealthSupplier;
        this.log = logProvider.getLog( getClass() );
        this.entryCache =  new LruCache<>( "raft-log-entry-cache", entryCacheSize );

        directory.mkdirs();

        VersionIndexRanges ranges = new VersionIndexRanges();
        logFiles = new PhysicalRaftLogFiles( directory, fileSystem, marshal, ranges );

        LogHeaderCache logHeaderCache = new LogHeaderCache( headerCacheSize );
        logFile = new PhysicalRaftLogFile( fileSystem, logFiles, rotateAtSize,
                appendIndex::get, monitor, logHeaderCache );

        LogFileInformation logFileInformation = new PhysicalRaftLogFileInformation( logFiles, logHeaderCache,
                this::appendIndex, version -> 0L );
        LogPruneStrategy logPruneStrategy = fromConfigValue( fileSystem, logFileInformation, logFiles, pruningConf ) ;
        this.logPruning = new LogPruningImpl( logPruneStrategy, logProvider );

        this.metadataCache = raftLogMetadataCache;
        SingleVersionReader reader = new SingleVersionReader( logFiles, fileSystem, marshal );
        entryStore = new VersionBridgingRaftEntryStore( ranges, reader, metadataCache );
    }

    @Override
    public long append( RaftLogEntry entry ) throws IOException
    {
        if ( entry.term() >= term )
        {
            term = entry.term();
        }
        else
        {
            throw new IllegalStateException( format( "Non-monotonic term %d for entry %s in term %d",
                    entry.term(), entry.toString(), term ) );
        }

        long newAppendIndex = appendIndex.incrementAndGet();

        LogPositionMarker entryStartPosition = writer.getCurrentPosition( positionMarker );
        RaftLogAppendRecord.write( writer, marshal, newAppendIndex, term, entry.content() );
        writer.prepareForFlush().flush();
        entryCache.put( newAppendIndex, entry );
        metadataCache.cacheMetadata( newAppendIndex, entry.term(), entryStartPosition.newPosition() );

        if( logRotation.rotateLogIfNeeded( LogAppendEvent.NULL ) )
        {
            RaftLogContinuationRecord.write( writer, newAppendIndex, term );
            writer.prepareForFlush().flush();
        }

        return newAppendIndex;
    }

    @Override
    public void truncate( long fromIndex ) throws IOException, RaftLogCompactedException
    {
        if ( fromIndex <= commitIndex )
        {
            throw new IllegalArgumentException( format( "cannot truncate (%d) at or before the commit index (%d)",
                    fromIndex, commitIndex ) );
        }

        if ( appendIndex.get() < fromIndex )
        {
            throw new IllegalArgumentException( "Cannot truncate at index " + fromIndex + " when append index is " + appendIndex );
        }

        long newAppendIndex = fromIndex - 1;
        long newTerm = readEntryTerm( newAppendIndex );

        entryCache.clear();
        metadataCache.removeUpwardsFrom( fromIndex );

        appendIndex.set( newAppendIndex );
        logRotation.rotateLogFile();

        term = newTerm;
        RaftLogContinuationRecord.write( writer, newAppendIndex - 1, term );
        writer.prepareForFlush().flush();
    }

    @Override
    public long appendIndex()
    {
        return appendIndex.get();
    }

    @Override
    public long prevIndex()
    {
        return prevIndex;
    }

    @Override
    public RaftLogCursor getEntryCursor( long fromIndex ) throws IOException, RaftLogCompactedException
    {
        final IOCursor<RaftLogAppendRecord> inner = entryStore.getEntriesFrom( fromIndex );
        return new RaftLogCursor()
        {
            private CursorValue<RaftLogEntry> current = new CursorValue<>();
            private long index = fromIndex - 1;

            @Override
            public boolean next() throws IOException
            {
                boolean hasNext = inner.next();
                if ( hasNext )
                {
                    current.set( inner.get().logEntry() );
                    index++;
                }
                else
                {
                    current.invalidate();
                }
                return hasNext;
            }

            @Override
            public void close() throws IOException
            {
                inner.close();
            }

            @Override
            public long index()
            {
                return index;
            }

            @Override
            public RaftLogEntry get()
            {
                return current.get();
            }
        };
    }

    @Override
    public long skip( long index, long term ) throws IOException
    {
        if( appendIndex.get() < index )
        {
            logRotation.rotateLogFile();
            RaftLogContinuationRecord.write( writer, index, term );
            writer.prepareForFlush().flush();

            prevIndex = index;
            prevTerm = term;
            appendIndex.set( index );

            metadataCache.clear();
        }

        return appendIndex.get();
    }

    private RaftLogEntry readLogEntry( long logIndex ) throws IOException, RaftLogCompactedException
    {
        RaftLogEntry entry = entryCache.get( logIndex );
        if( entry != null )
        {
            return entry;
        }

        try ( IOCursor<RaftLogAppendRecord> entriesFrom = entryStore.getEntriesFrom( logIndex ) )
        {
            while ( entriesFrom.next() )
            {
                RaftLogAppendRecord raftLogAppendRecord = entriesFrom.get();
                if ( raftLogAppendRecord.logIndex() == logIndex )
                {
                    RaftLogEntry toReturn = raftLogAppendRecord.logEntry();
                    entryCache.put( logIndex, toReturn );
                    return toReturn;
                }
                else if ( raftLogAppendRecord.logIndex() > logIndex )
                {
                    throw new IllegalStateException( format( "Asked for index %d but got up to %d without " +
                            "finding it.", logIndex, raftLogAppendRecord.logIndex() ) );
                }
            }
        }
        return null;
    }

    @Override
    public long readEntryTerm( long logIndex ) throws IOException, RaftLogCompactedException
    {
        // Index -1 is not an existing log index, but represents the beginning of the log.
        // It is a valid value to request the term for, and the term is -1.
        if( logIndex == prevIndex )
        {
            return prevTerm;
        }
        else if ( logIndex < prevIndex || logIndex > appendIndex.get() )
        {
            return -1;
        }

        RaftLogMetadataCache.RaftLogEntryMetadata metadata = metadataCache.getMetadata( logIndex );
        if( metadata != null )
        {
            return metadata.getEntryTerm();
        }

        long resultTerm = -1;

        RaftLogEntry raftLogEntry = readLogEntry( logIndex );
        if ( raftLogEntry != null )
        {
            resultTerm = raftLogEntry.term();
        }

        return resultTerm;
    }

    @Override
    public void init() throws IOException
    {
        logFiles.init();
        logFile.init();
    }

    @Override
    public void start() throws IOException, RaftLogCompactedException
    {
        this.logRotation = new LogRotationImpl( new LoggingLogFileMonitor( log ), logFile, databaseHealthSupplier.get() );

        logFile.start();

        recoverContinuationRecord();

        restorePrevIndexAndTerm();
        restoreAppendIndex();

        this.writer = logFile.getWriter();
    }

    /** This is just a very basic "recovery" making it so that a continuation record exists in the very first file right after creation. */
    private void recoverContinuationRecord() throws IOException
    {
        LogPositionMarker logPosition = new LogPositionMarker();

        FlushablePositionAwareChannel writer = logFile.getWriter();
        writer.getCurrentPosition( logPosition );

        if( logPosition.getLogVersion() == 0 && logPosition.getByteOffset() == LogHeader.LOG_HEADER_SIZE )
        {
            RaftLogContinuationRecord.write( writer, -1, -1 );
            writer.prepareForFlush().flush();
        }
    }

    @Override
    public long prune( long safeIndex ) throws IOException
    {
        final long logVersionToPrune = findLogVersionToPrune( safeIndex );

        if ( logVersionToPrune != -1 )
        {
            logPruning.pruneLogs( logVersionToPrune );
            restorePrevIndexAndTerm();
            metadataCache.removeUpTo( prevIndex - 1 );
        }

        return prevIndex;
    }

    /**
     * Returns the log file version that contains entries which all have index less than the safeIndex argument.
     * @param safeIndex The index value from which all entries should be less than in the returned log version
     */
    public long findLogVersionToPrune( long safeIndex ) throws IOException
    {
        final LogPosition[] firstFileToPrune = {LogPosition.UNSPECIFIED};

        logFile.accept( ( position, ignored, lastIndex ) -> {
            if ( lastIndex < safeIndex )
            {
                firstFileToPrune[0] = position;
                return false;
            }
            return true; // keep going
        } );

        return firstFileToPrune[0].equals( LogPosition.UNSPECIFIED ) ? -1 : firstFileToPrune[0].getLogVersion();
    }

    private void restorePrevIndexAndTerm() throws IOException
    {
        final Reference<LogPosition> firstFile = new Reference<>( null );

        logFile.accept( ( position, ignored1, ignored2 ) -> {
            firstFile.set( position );
            return true;
        } );

        if( firstFile.get() == null )
        {
            throw new IOException( "No first log file found" );
        }

        ReadableLogChannel reader = logFile.getReader( firstFile.get() );

        RaftRecordCursor<ReadableLogChannel> recordCursor = new RaftRecordCursor<>( reader, marshal );
        recordCursor.next();
        RaftLogContinuationRecord cont = (RaftLogContinuationRecord) recordCursor.get();

        prevIndex = cont.prevLogIndex();
        prevTerm = cont.prevLogTerm();

        log.info( "Restored prev index at %d", prevIndex );
        log.info( "Restored prev term at %d", prevTerm );
    }

    private void restoreAppendIndex() throws IOException, RaftLogCompactedException
    {
        long restoredAppendIndex = prevIndex;
        try( IOCursor<RaftLogAppendRecord> cursor = entryStore.getEntriesFrom( prevIndex + 1 ) )
        {
            while( cursor.next() )
            {
                restoredAppendIndex = cursor.get().logIndex();
            }
        }

        appendIndex.set( restoredAppendIndex );
        log.info( "Restored append index at %d", restoredAppendIndex );
    }

    @Override
    public void stop() throws Throwable
    {
        logFile.stop();
        this.writer = null;
    }

    @Override
    public void shutdown() throws Throwable
    {
        logFile.shutdown();
    }

    public enum RecordType
    {
        APPEND( (byte) 0 ), CONTINUATION( (byte) 1 );

        private final byte value;

        RecordType( byte value )
        {
            this.value = value;
        }

        public byte value()
        {
            return value;
        }

        public static RecordType forValue( byte value )
        {
            switch ( value )
            {
                case 0:
                    return APPEND;
                case 1:
                    return CONTINUATION;
                default:
                    throw new IllegalArgumentException( "Value " + value + " is not a known entry type" );
            }
        }
    }
}
