import React, { useMemo } from "react";
import styled from "styled-components";
import { useIntl } from "react-intl";

import StatusIcon from "components/StatusIcon";
import { StatusIconStatus } from "components/StatusIcon/StatusIcon";
import { ConnectorIcon } from "components/ConnectorIcon";

import { Status } from "../types";

type IProps = {
  value: string;
  enabled?: boolean;
  status?: string | null;
  icon?: boolean;
  img?: string;
};

const Content = styled.div`
  display: flex;
  align-items: center;
  font-weight: 500;
`;

const Name = styled.div<{ enabled?: boolean }>`
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 500px;
  color: ${({ theme, enabled }) => (!enabled ? theme.greyColor40 : "inherit")};
`;

const Space = styled.div`
  width: 30px;
  height: 20px;
  opacity: 0;
`;

const Image = styled(ConnectorIcon)`
  margin-right: 6px;
`;

const NameCell: React.FC<IProps> = ({ value, enabled, status, icon, img }) => {
  const formatMessage = useIntl().formatMessage;
  const statusIconStatus = useMemo<StatusIconStatus | undefined>(
    () =>
      status === Status.EMPTY
        ? "empty"
        : status === Status.ACTIVE
        ? "success"
        : status === Status.INACTIVE
        ? "inactive"
        : undefined,
    [status]
  );
  const title =
    status === Status.EMPTY
      ? formatMessage({
          id: "connection.noSyncData",
        })
      : status === Status.INACTIVE
      ? formatMessage({
          id: "connection.disabledConnection",
        })
      : status === Status.ACTIVE
      ? formatMessage({
          id: "connection.successSync",
        })
      : formatMessage({
          id: "connection.failedSync",
        });

  return (
    <Content>
      {status ? <StatusIcon title={title} status={statusIconStatus} /> : <Space />}
      {icon && <Image small icon={img} />}
      <Name enabled={enabled}>{value}</Name>
    </Content>
  );
};

export default NameCell;
