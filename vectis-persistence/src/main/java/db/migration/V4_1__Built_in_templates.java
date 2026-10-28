// SPDX-License-Identifier: Apache-2.0
package db.migration;

import io.vectis.persistence.BuiltInTemplates;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Inserts the built-in template versions from their files (VEC-68 AC3). A Java migration because
 * {@code resolved} is the production resolver's output, which SQL cannot compute; it sits between
 * V4, which creates the tables, and V4.2, which pins existing workspaces to {@code scrum@1}.
 */
public class V4_1__Built_in_templates extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        BuiltInTemplates.seed(context.getConnection());
    }
}
