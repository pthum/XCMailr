/**  
 *  Copyright 2017 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License. 
 *
 */
package filters;

import java.util.Base64;

import org.apache.commons.lang3.StringUtils;

import com.google.inject.Inject;

import controllers.CachingSessionHandler;
import models.User;
import ninja.Context;
import ninja.Filter;
import ninja.FilterChain;
import ninja.Result;
import ninja.Results;

/**
 * Checks for Basic-Auth and returns unauthorized if authorization is invalid
 * 
 * @author Patrick Thum
 */
public class SecureApiFilter implements Filter
{
    @Inject
    CachingSessionHandler csh;

    @Override
    public Result filter(FilterChain chain, Context context)
    {
        String authHeader = context.getHeader("Authorization");
        if (StringUtils.isNotBlank(authHeader) && authHeader.contains("Basic "))
        {
            final String encAuth = authHeader.substring("Basic ".length());
            final String decAuth = new String(Base64.getDecoder().decode(encAuth));
            final String[] creds = decAuth.split(":");
            // TODO add caching in future
            // auth user
            User usr = User.auth(creds[0], creds[1]);

            if ((usr != null) && usr.isActive())
            {
                // add the user-object to the context to reduce the no. of connections to the caching server
                context.setAttribute("user", usr);
                // go to the next filter (or controller-method)
                return chain.next(context);
            }
        }
        return Results.forbidden().json().render("error", "Unauthorized");
    }
}
