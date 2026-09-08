// Saved-reports store types (Phase B / B19).

export interface ReportDefinition {
  id: number;
  name: string;
  reportType: string;
  params: string;
  owner: string | null;
  created: string;
}

export interface CreateReportDefinitionInput {
  name: string;
  reportType: string;
  params?: string;
  owner?: string;
}
