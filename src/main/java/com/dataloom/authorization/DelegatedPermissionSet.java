package com.dataloom.authorization;

import java.io.Serializable;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class DelegatedPermissionSet implements Set<Permission>, Serializable {
    private static final long     serialVersionUID = -1614439154999058363L;
    private final Set<Permission> permissions;

    public DelegatedPermissionSet( Set<Permission> permissions ) {
        this.permissions = permissions;
    }

    public static DelegatedPermissionSet wrap( Set<Permission> permissions ) {
        return new DelegatedPermissionSet( permissions );
    }

    public Set<Permission> unwrap() {
        return permissions;
    }

    public void forEach( Consumer<? super Permission> action ) {
        permissions.forEach( action );
    }

    public int size() {
        return permissions.size();
    }

    public boolean isEmpty() {
        return permissions.isEmpty();
    }

    public boolean contains( Object o ) {
        return permissions.contains( o );
    }

    public Iterator<Permission> iterator() {
        return permissions.iterator();
    }

    public Object[] toArray() {
        return permissions.toArray();
    }

    public <T> T[] toArray( T[] a ) {
        return permissions.toArray( a );
    }

    public boolean add( Permission e ) {
        return permissions.add( e );
    }

    public boolean remove( Object o ) {
        return permissions.remove( o );
    }

    public boolean containsAll( Collection<?> c ) {
        return permissions.containsAll( c );
    }

    public boolean addAll( Collection<? extends Permission> c ) {
        return permissions.addAll( c );
    }

    public boolean retainAll( Collection<?> c ) {
        return permissions.retainAll( c );
    }

    public boolean removeAll( Collection<?> c ) {
        return permissions.removeAll( c );
    }

    public void clear() {
        permissions.clear();
    }

    public boolean equals( Object o ) {
        return permissions.equals( o );
    }

    public int hashCode() {
        return permissions.hashCode();
    }

    public Spliterator<Permission> spliterator() {
        return permissions.spliterator();
    }

    public boolean removeIf( Predicate<? super Permission> filter ) {
        return permissions.removeIf( filter );
    }

    public Stream<Permission> stream() {
        return permissions.stream();
    }

    public Stream<Permission> parallelStream() {
        return permissions.parallelStream();
    }
}
