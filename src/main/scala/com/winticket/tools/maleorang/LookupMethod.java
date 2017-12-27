package com.winticket.tools.maleorang;

import com.ecwid.maleorang.MailchimpClient;
import com.ecwid.maleorang.MailchimpMethod;
import com.ecwid.maleorang.MailchimpObject;
import com.ecwid.maleorang.annotation.*;
import org.apache.commons.codec.digest.DigestUtils;


/**
 * Implement MailChimp lookup method
 */
public class LookupMethod {
    private final String apiKey, listId;

    @Method(httpMethod = HttpMethod.GET, version = APIVersion.v3_0, path = "/lists/{list_id}/members/{subscriber_hash}")
    private static class LookupRequest extends MailchimpMethod<LookupResponse> {
        /**
         * This param will be included into the endpoint path.
         */
        @PathParam
        public final String list_id;

        /**
         * This param will be included into the endpoint path.
         */
        @PathParam
        public final String subscriber_hash;


        public LookupRequest(String listId, String email) {
            this.list_id = listId;
            this.subscriber_hash = DigestUtils.md5Hex(email.toLowerCase());
        }
    }

    private static class LookupResponse extends MailchimpObject {
        @Field
        public String id;

        @Field
        public String status;
    }

    public LookupMethod(String apiKey, String listId) {
        this.apiKey = apiKey;
        this.listId = listId;
    }


    public String run(String email) throws Exception {
        MailchimpClient client = new MailchimpClient(apiKey);
        try {
            LookupRequest request = new LookupRequest(listId, email);
            LookupResponse response = client.execute(request);
            return response.status;
        } finally {
            client.close();
        }
    }
}