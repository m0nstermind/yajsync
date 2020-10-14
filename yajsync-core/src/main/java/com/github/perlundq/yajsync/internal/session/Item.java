/*
 * Rsync file change information flag
 *
 * Copyright (C) 1996-2011 by Andrew Tridgell, Wayne Davison, and others
 * Copyright (C) 2013, 2014 Per Lundqvist
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
 */
package com.github.perlundq.yajsync.internal.session;

final class Item
{
    private Item() {}

    public static final char NO_CHANGE          = 0;      // used
    public static final char REPORT_ATIME       = (1<<0);
    public static final char REPORT_CHANGE      = (1<<1);
    public static final char REPORT_SIZE        = (1<<2); // used
    public static final char REPORT_TIMEFAIL    = (1<<2);
    public static final char REPORT_TIME        = (1<<3); // used
    public static final char REPORT_PERMS       = (1<<4); // used
    public static final char REPORT_OWNER       = (1<<5); // used
    public static final char REPORT_GROUP       = (1<<6); // used
    public static final char REPORT_ACL         = (1<<7);
    public static final char REPORT_XATTR       = (1<<8);
    public static final char BASIS_TYPE_FOLLOWS = (1<<11);
    public static final char XNAME_FOLLOWS      = (1<<12);
    public static final char IS_NEW             = (1<<13); // used
    public static final char LOCAL_CHANGE       = (1<<14); // used
    public static final char TRANSFER           = (1<<15); // used

    private static final char _supported = IS_NEW |
                                           LOCAL_CHANGE |
                                           REPORT_CHANGE |
                                           REPORT_OWNER |
                                           REPORT_GROUP |
                                           REPORT_PERMS |
                                           REPORT_SIZE |
                                           REPORT_TIME |
                                           TRANSFER | 
                                           XNAME_FOLLOWS;

    public static boolean isValidItem(int flags)
    {
        return (flags | _supported) == _supported;
    }
    
    public static String toString( int flags ) {
        if ( flags == 0 )
            return "no change";
        
        StringBuffer sb = new StringBuffer( Integer.toHexString( flags ) ).append( ':' );
        
        if ((flags & REPORT_ATIME      )!=0 ) sb.append("REPORT_ATIME ");
        if ((flags & REPORT_CHANGE     )!=0 ) sb.append("REPORT_CHANGE ");
        if ((flags & REPORT_SIZE       )!=0 ) sb.append("REPORT_SIZE ");
        if ((flags & REPORT_TIMEFAIL   )!=0 ) sb.append("REPORT_TIMEFAIL ");
        if ((flags & REPORT_TIME       )!=0 ) sb.append("REPORT_TIME ");
        if ((flags & REPORT_PERMS      )!=0 ) sb.append("REPORT_PERMS ");
        if ((flags & REPORT_OWNER      )!=0 ) sb.append("REPORT_OWNER ");
        if ((flags & REPORT_GROUP      )!=0 ) sb.append("REPORT_GROUP ");
        if ((flags & REPORT_ACL        )!=0 ) sb.append("REPORT_ACL ");
        if ((flags & REPORT_XATTR      )!=0 ) sb.append("REPORT_XATTR ");
        if ((flags & BASIS_TYPE_FOLLOWS)!=0 ) sb.append("BASIS_TYPE_FOLLOWS ");
        if ((flags & XNAME_FOLLOWS     )!=0 ) sb.append("XNAME_FOLLOWS ");
        if ((flags & IS_NEW            )!=0 ) sb.append("IS_NEW ");
        if ((flags & LOCAL_CHANGE      )!=0 ) sb.append("LOCAL_CHANGE ");
        if ((flags & TRANSFER          )!=0 ) sb.append("TRANSFER");

        return sb.toString();
    }
}
