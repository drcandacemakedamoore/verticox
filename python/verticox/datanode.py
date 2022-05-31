import logging
from concurrent.futures import ThreadPoolExecutor
from typing import Optional

import grpc
import numpy as np
from sksurv.datasets import load_whas500

from verticox.grpc.datanode_pb2 import LocalParameters, NumFeatures, \
    NumSamples, Empty
from verticox.grpc.datanode_pb2_grpc import DataNodeServicer, add_DataNodeServicer_to_server

logger = logging.getLogger(__name__)

RHO = 0.25
PORT = 8888
MAX_WORKERS = 1


class DataNode(DataNodeServicer):
    def __init__(self, features: np.array = None, event_times: Optional[np.array] = None,
                 right_censored: Optional[np.array] = None, rho: float = RHO):
        """

        Args:
            features:
            event_times:
            rho:
        """
        self.features = features
        self.num_features = self.features.shape[1]
        self.event_times = event_times
        self.right_censored = right_censored
        self.rho = rho

        # Parts that stay constant over iterations
        # Square all covariates and sum them together
        # The formula says for every patient, x needs to be multiplied by itself.
        # Squaring all covariates with themselves comes down to the same thing since x_nk is supposed to
        # be one-dimensional
        self.features_multiplied = DataNode._multiply_covariates(features)
        self.features_sum = features.sum(axis=0)
        self.num_samples = self.features.shape[0]

        self.gamma = np.zeros((self.num_samples,))
        self.z = np.zeros((self.num_samples,))
        self.sigma = np.zeros((self.num_samples,))

        self.sigma_all = None
        self.gamma_all = None
        self.beta = np.zeros((self.num_features))

    def fit(self, request, context=None):
        logger.info('Performing local update...')
        sigma, beta = DataNode._local_update(self.features, self.z, self.gamma, self.rho,
                                             self.features_multiplied, self.features_sum)

        self.sigma = sigma
        self.beta = beta

        logging.debug(f'Updated sigma to {self.sigma}, beta to {self.beta}')

        response = LocalParameters(gamma=self.gamma, sigma=sigma.tolist())
        logger.info('Finished local update, returning results.')

        return response

    def updateParameters(self, request, context=None):
        self.z = np.array(request.z)
        self.sigma_all = np.array(request.sigma)
        self.gamma_all = np.array(request.gamma)

        return Empty()

    def computeGamma(self, request, context=None):
        """
        Equation 18
        Args:
            request:
            context:

        Returns:

        """
        self.gamma = self.gamma_all + self.rho * self.sigma_all - self.z

        return Empty()

    def getNumFeatures(self, request, context=None):
        num_features = self.num_features
        return NumFeatures(numFeatures=num_features)

    def getNumSamples(self, request, context=None):
        num_samples = self.num_samples

        return NumSamples(numSamples=num_samples)

    @staticmethod
    def _sum_covariates(covariates: np.array):
        return np.sum(covariates, axis=0)

    @staticmethod
    def _multiply_covariates(features: np.array):
        return np.square(features).sum()

    @staticmethod
    def _elementwise_multiply_sum(one_dim: np.array, two_dim: np.array):
        """
        Every element in one_dim does elementwise multiplication with its corresponding row in two_dim.

        All rows of the result will be summed together vertically.
        """
        multiplied = np.zeros(two_dim.shape)
        for i in range(one_dim.shape[0]):
            multiplied[i] = one_dim[i] * two_dim[i]

        return multiplied.sum(axis=0)

    @staticmethod
    def _compute_beta(features: np.array, z: np.array, gamma: np.array, rho,
                      multiplied_covariates, covariates_sum):
        first_component = 1 / (rho * multiplied_covariates)

        pz = rho * z

        second_component = \
            DataNode._elementwise_multiply_sum(pz - gamma, features) + covariates_sum

        return second_component / first_component

    @staticmethod
    def _compute_sigma(beta, covariates):
        return np.matmul(covariates, beta)

    @staticmethod
    def _local_update(covariates: np.array, z: np.array, gamma: np.array, rho,
                      covariates_multiplied, covariates_sum):
        beta = DataNode._compute_beta(covariates, z, gamma, rho, covariates_multiplied,
                                      covariates_sum)

        return DataNode._compute_sigma(beta, covariates), beta


def serve():
    features, events = load_whas500()
    features = features.values.astype(float)
    server = grpc.server(ThreadPoolExecutor(max_workers=MAX_WORKERS))
    add_DataNodeServicer_to_server(DataNode(features=features, event_times=events), server)
    server.add_insecure_port(f'[::]:{PORT}')
    logger.info(f'Starting datanode on port {PORT}')
    server.start()
    server.wait_for_termination()


if __name__ == '__main__':
    logging.basicConfig(level=logging.DEBUG)
    serve()
