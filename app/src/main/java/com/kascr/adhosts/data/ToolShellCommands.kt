package com.kascr.adhosts.data

import com.topjohnwu.superuser.Shell

/** Keeps tool scripts and their exit commands out of libsu's persistent shell. */
internal object ToolShellCommands {
    internal fun buildCommand(commands: List<String>): String {
        require(commands.isNotEmpty())
        val script = commands.joinToString("\n") { command ->
            "sh -c ${RootEnvironment.quote(command)} || exit ${'$'}?"
        }
        return "sh -c ${RootEnvironment.quote(script)}"
    }

    fun execute(commands: List<String>): Shell.Result = Shell.cmd(buildCommand(commands))
        .to(ArrayList<String>(), ArrayList<String>()).exec()
}
