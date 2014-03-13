package org.apache.cassandra.stress.settings;
/*
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 */


import java.util.HashMap;
import java.util.Map;

public enum Command
{

    READ(false, "Standard1", "Super1",
            "Multiple concurrent reads - the cluster must first be populated by a write test",
            CommandCategory.BASIC
    ),
    WRITE(true, "Standard1", "Super1",
            "insert",
            "Multiple concurrent writes against the cluster",
            CommandCategory.BASIC
    ),
    MIXED(true, null, null,
            "Interleaving of any basic commands, with configurable ratio and distribution - the cluster must first be populated by a write test",
            CommandCategory.MIXED
    ),
    RANGESLICE(false, "Standard1", "Super1",
            "Range slice queries - the cluster must first be populated by a write test",
            CommandCategory.MULTI
    ),
    IRANGESLICE(false, "Standard1", "Super1",
            "Range slice queries through a secondary index. The cluster must first be populated by a write test, with indexing enabled.",
            CommandCategory.BASIC
    ),
    READMULTI(false, "Standard1", "Super1",
            "multi_read",
            "Multiple concurrent reads fetching multiple rows at once. The cluster must first be populated by a write test.",
            CommandCategory.MULTI
    ),
    COUNTERWRITE(true, "Counter1", "SuperCounter1",
            "counter_add",
            "Multiple concurrent updates of counters.",
            CommandCategory.BASIC
    ),
    COUNTERREAD(false, "Counter1", "SuperCounter1",
            "counter_get",
            "Multiple concurrent reads of counters. The cluster must first be populated by a counterwrite test.",
            CommandCategory.BASIC
    ),

    HELP(false, null, null, "-?", "Print help for a command or option", null),
    PRINT(false, null, null, "Inspect the output of a distribution definition", null),
    LEGACY(false, null, null, "Legacy support mode", null)

    ;

    private static final Map<String, Command> LOOKUP;
    static
    {
        final Map<String, Command> lookup = new HashMap<>();
        for (Command cmd : values())
        {
            lookup.put(cmd.toString().toLowerCase(), cmd);
            if (cmd.extraName != null)
                lookup.put(cmd.extraName, cmd);
        }
        LOOKUP = lookup;
    }

    public static Command get(String command)
    {
        return LOOKUP.get(command.toLowerCase());
    }

    public final boolean updates;
    public final CommandCategory category;
    public final String extraName;
    public final String description;
    public final String table;
    public final String supertable;

    Command(boolean updates, String table, String supertable, String description, CommandCategory category)
    {
        this(updates, table, supertable, null, description, category);
    }

    Command(boolean updates, String table, String supertable, String extra, String description, CommandCategory category)
    {
        this.table = table;
        this.supertable = supertable;
        this.updates = updates;
        this.category = category;
        this.extraName = extra;
        this.description = description;
    }

    public void printHelp()
    {
        helpPrinter().run();
    }

    public final Runnable helpPrinter()
    {
        switch (this)
        {
            case PRINT:
                return SettingsMisc.printHelpPrinter();
            case HELP:
                return SettingsMisc.helpHelpPrinter();
            case LEGACY:
                return Legacy.helpPrinter();
        }
        switch (category)
        {
            case BASIC:
            case MULTI:
                return SettingsCommand.helpPrinter(this);
            case MIXED:
                return SettingsCommandMixed.helpPrinter();
        }
        throw new AssertionError();
    }

}