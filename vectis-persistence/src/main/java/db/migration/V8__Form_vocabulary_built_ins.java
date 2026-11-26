// SPDX-License-Identifier: Apache-2.0
package db.migration;

import io.vectis.persistence.BuiltInTemplates;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Ships the form vocabulary's built-ins (VEC-70) to a database that already ran V4.1: the
 * revised {@code scrum@1} (and {@code hybrid@1}, which resolves through it) replace what was
 * stored, and {@code stage-gate@1} is inserted. On a new database V4.1 has already stored the
 * revised files, and this changes nothing.
 */
public class V8__Form_vocabulary_built_ins extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        BuiltInTemplates.revise(context.getConnection());
    }
}
