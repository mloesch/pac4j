package org.pac4j.core.profile;

import lombok.ToString;

/**
 * Anonymous profile. Not to be used except for advanced use cases.
 *
 * @author Jerome Leleu
 * @since 1.8.1
 */
@ToString(callSuper = true)
public class AnonymousProfile extends CommonProfile {

    private static final long serialVersionUID = -7377022639833719511L;

    public static final AnonymousProfile INSTANCE = new AnonymousProfile();

    public AnonymousProfile() {
        setId("anonymous");
    }
}
