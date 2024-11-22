/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use std::convert::Infallible;

use aws_smithy_http::url::Url;
use tower::Layer;
use tower::Service;

use crate::body::BoxBody;
use crate::response::IntoResponse;
use crate::routing::method_disallowed;
use crate::routing::tiny_map::TinyMap;
use crate::routing::Route;
use crate::routing::Router;

use thiserror::Error;

use super::AwsQuery;

/// An AWS Query routing error.
#[derive(Debug, Error)]
pub enum Error {
    /// Method was not `POST`.
    #[error("method not POST")]
    MethodNotAllowed,
    /// Operation not found.
    #[error("operation not found")]
    NotFound,
}

impl IntoResponse<AwsQuery> for Error {
    fn into_response(self) -> http::Response<BoxBody> {
        match self {
            Error::NotFound => http::Response::builder()
                .status(http::StatusCode::NOT_FOUND)
                .header(http::header::CONTENT_TYPE, "text/xml")
                .body(crate::body::to_boxed("{}"))
                .expect("invalid HTTP response for REST JSON 1 routing error; please file a bug report under https://github.com/smithy-lang/smithy-rs/issues"),
            Error::MethodNotAllowed => method_disallowed(),
        }
    }
}


// This constant determines when the `TinyMap` implementation switches from being a `Vec` to a
// `HashMap`. This is chosen to be 15 as a result of the discussion around
// https://github.com/smithy-lang/smithy-rs/pull/1429#issuecomment-1147516546
pub(crate) const ROUTE_CUTOFF: usize = 15;

/// A [`Router`] supporting [AWS JSON 1.0] and [AWS JSON 1.1] protocols.
///
/// [AWS JSON 1.0]: https://smithy.io/2.0/aws/protocols/aws-json-1_0-protocol.html
/// [AWS JSON 1.1]: https://smithy.io/2.0/aws/protocols/aws-json-1_1-protocol.html
#[derive(Debug, Clone)]
pub struct AwsQueryRouter<S> {
    routes: TinyMap<&'static str, S, ROUTE_CUTOFF>,
}

impl<S> AwsQueryRouter<S> {
    /// Applies a [`Layer`] uniformly to all routes.
    pub fn layer<L>(self, layer: L) -> AwsQueryRouter<L::Service>
    where
        L: Layer<S>,
    {
        AwsQueryRouter {
            routes: self
                .routes
                .into_iter()
                .map(|(key, route)| (key, layer.layer(route)))
                .collect(),
        }
    }

    /// Applies type erasure to the inner route using [`Route::new`].
    pub fn boxed<B>(self) -> AwsQueryRouter<Route<B>>
    where
        S: Service<http::Request<B>, Response = http::Response<BoxBody>, Error = Infallible>,
        S: Send + Clone + 'static,
        S::Future: Send + 'static,
    {
        AwsQueryRouter {
            routes: self.routes.into_iter().map(|(key, s)| (key, Route::new(s))).collect(),
        }
    }
}

impl<B, S> Router<B> for AwsQueryRouter<S>
where
    S: Clone,
{
    type Service = S;
    type Error = Error;

    fn match_route(&self, request: &http::Request<B>) -> Result<S, Self::Error> {
        // Only `Method::POST` is allowed.
        if request.method() != http::Method::POST {
            return Err(Error::MethodNotAllowed);
        }

        // The URI must be root
        let url = Url::parse(&request.uri().to_string()).map_err(|_e| Error::NotFound)?;

        let (_, target) = url
            .query_pairs()
            .find(|(k, _v)| {
                k == "Action"
            })
            .ok_or({
                Error::NotFound
            })?;

        // Lookup in the `TinyMap` for a route for the target.
        let route = self.routes.get(target.to_string().as_str()).ok_or(Error::NotFound)?;
        Ok(route.clone())
    }
}

impl<S> FromIterator<(&'static str, S)> for AwsQueryRouter<S> {
    #[inline]
    fn from_iter<T: IntoIterator<Item = (&'static str, S)>>(iter: T) -> Self {
        Self {
            routes: iter.into_iter().collect(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{protocol::test_helpers::req, routing::Router};

    use http::Method;
    use pretty_assertions::assert_eq;

    #[tokio::test]
    async fn simple_routing() {
        let routes = vec![("Service.Operation")];
        let router: AwsQueryRouter<_> = routes.clone().into_iter().map(|operation| (operation, ())).collect();

        // Valid request, should match.
        router
            .match_route(&req(
                &Method::POST,
                "http://localhost/something?Action=Service.Operation",
                None,
            ))
            .unwrap();

        // No headers, should return `MissingAction`.
        let res = router.match_route(&req(&Method::POST, "/", None));
        assert_eq!(res.unwrap_err().to_string(), Error::NotFound.to_string());

        // Wrong HTTP method, should return `MethodNotAllowed`.
        let res = router.match_route(&req(&Method::GET, "/", None));
        assert_eq!(res.unwrap_err().to_string(), Error::MethodNotAllowed.to_string());
    }
}
