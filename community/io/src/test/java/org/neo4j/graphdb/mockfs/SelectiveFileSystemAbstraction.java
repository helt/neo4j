/*
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
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
 */
package org.neo4j.graphdb.mockfs;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;

import org.neo4j.function.Function;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.fs.StoreChannel;

/**
 * Allows you to select different file system behaviour for one file and a different file system behaviour for
 * everyone else
 * e.g. Adversarial behaviour for the file under tests and normal behaviour for all other files.
 */
public class SelectiveFileSystemAbstraction implements FileSystemAbstraction
{
    private final File specialFile;
    private final FileSystemAbstraction specialFileSystem;
    private final FileSystemAbstraction defaultFileSystem;

    public SelectiveFileSystemAbstraction( File specialFile,
                                           FileSystemAbstraction specialFileSystem,
                                           FileSystemAbstraction defaultFileSystem )
    {
        this.specialFile = specialFile;
        this.specialFileSystem = specialFileSystem;
        this.defaultFileSystem = defaultFileSystem;
    }

    @Override
    public StoreChannel open( File fileName, String mode ) throws IOException
    {
        return chooseFileSystem( fileName ).open( fileName, mode );
    }

    @Override
    public OutputStream openAsOutputStream( File fileName, boolean append ) throws IOException
    {
        return chooseFileSystem( fileName ).openAsOutputStream( fileName, append );
    }

    @Override
    public InputStream openAsInputStream( File fileName ) throws IOException
    {
        return chooseFileSystem( fileName ).openAsInputStream( fileName );
    }

    @Override
    public Reader openAsReader( File fileName, Charset charset ) throws IOException
    {
        return chooseFileSystem( fileName ).openAsReader( fileName, charset );
    }

    @Override
    public Writer openAsWriter( File fileName, Charset charset, boolean append ) throws IOException
    {
        return chooseFileSystem( fileName ).openAsWriter( fileName, charset, append );
    }

    @Override
    public StoreChannel create( File fileName ) throws IOException
    {
        return chooseFileSystem( fileName ).create( fileName );
    }

    @Override
    public boolean fileExists( File fileName )
    {
        return chooseFileSystem( fileName ).fileExists( fileName );
    }

    @Override
    public boolean mkdir( File fileName )
    {
        return chooseFileSystem( fileName ).mkdir( fileName );
    }

    @Override
    public void mkdirs( File fileName ) throws IOException
    {
        chooseFileSystem( fileName ).mkdirs( fileName );
    }

    @Override
    public long getFileSize( File fileName )
    {
        return chooseFileSystem( fileName ).getFileSize( fileName );
    }

    @Override
    public boolean deleteFile( File fileName )
    {
        return chooseFileSystem( fileName ).deleteFile( fileName );
    }

    @Override
    public void deleteRecursively( File directory ) throws IOException
    {
        chooseFileSystem( directory ).deleteRecursively( directory );
    }

    @Override
    public boolean renameFile( File from, File to ) throws IOException
    {
        return chooseFileSystem( from ).renameFile( from, to );
    }

    @Override
    public File[] listFiles( File directory )
    {
        return chooseFileSystem( directory ).listFiles( directory );
    }

    @Override
    public File[] listFiles( File directory, FilenameFilter filter )
    {
        return chooseFileSystem( directory ).listFiles( directory, filter );
    }

    @Override
    public boolean isDirectory( File file )
    {
        return chooseFileSystem( file ).isDirectory( file );
    }

    @Override
    public void moveToDirectory( File file, File toDirectory ) throws IOException
    {
        chooseFileSystem( file ).moveToDirectory( file, toDirectory );
    }

    @Override
    public void copyFile( File from, File to ) throws IOException
    {
        chooseFileSystem( from ).copyFile( from, to );
    }

    @Override
    public void copyRecursively( File fromDirectory, File toDirectory ) throws IOException
    {
        chooseFileSystem( fromDirectory ).copyRecursively( fromDirectory, toDirectory );
    }

    @Override
    public <K extends ThirdPartyFileSystem> K getOrCreateThirdPartyFileSystem( Class<K> clazz, Function<Class<K>, K>
            creator )
    {
        return defaultFileSystem.getOrCreateThirdPartyFileSystem( clazz, creator );
    }

    @Override
    public void truncate( File path, long size ) throws IOException
    {
        chooseFileSystem( path ).truncate( path, size );
    }

    private FileSystemAbstraction chooseFileSystem( File file )
    {
        return file.equals( specialFile ) ? specialFileSystem : defaultFileSystem;
    }
}
