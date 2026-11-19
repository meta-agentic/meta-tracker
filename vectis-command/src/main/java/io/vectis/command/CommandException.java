// SPDX-License-Identifier: Apache-2.0
package io.vectis.command;

/**
 * Why a command was refused. The kinds are the client errors of the API conventions
 * (ADR-VEC-05 D2), named for what went wrong rather than by a status code: a channel maps each to
 * its own wire form, and the command layer knows nothing of HTTP.
 */
public abstract sealed class CommandException extends RuntimeException
        permits UnknownKeyException, ReadOnlyWorkspaceException, VersionRequiredException, StaleVersionException,
        CommandRejectedException {

    CommandException(String message) {
        super(message);
    }
}
