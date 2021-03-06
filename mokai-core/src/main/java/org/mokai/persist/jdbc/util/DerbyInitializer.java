package org.mokai.persist.jdbc.util;

public class DerbyInitializer extends DBInitializer {

	@Override
	public String messagesTableScript() {
		String script = "CREATE TABLE message (" +
			"id_message BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1), " +
			"account_message VARCHAR(30), " +
			"reference_message VARCHAR(100), " +
			"flow_message SMALLINT NOT NULL, " +
			"source_message VARCHAR(30) NOT NULL, " +
			"sourcetype_message SMALLINT NOT NULL, " +
			"destination_message VARCHAR(30), " +
			"destinationtype_message SMALLINT, " +
			"status_message SMALLINT NOT NULL, " +
			"to_message VARCHAR(30), " +
			"from_message VARCHAR(30), " +
			"text_message VARCHAR(255), " +
			"messageid_message VARCHAR(50), " +
			"commandstatus_message INTEGER, " +
			"creation_time TIMESTAMP)";
		
		return script;
	}

	@Override
	public String getDbSchema() {
		return "APP";
	}
}
